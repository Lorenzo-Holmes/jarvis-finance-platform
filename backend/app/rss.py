"""JARVIS RSS 信息源模块（Phase 3 Information Agent 的 MVP 地基）。

职责边界（与三栈架构一致）：
  - 本模块只做「资讯源配置 / RSS 抓取 / 文章标准化 / 去重」。
  - 文章的业务持久化、权限、配额由 Java 主后端负责；本模块的存储是**进程内存**，
    进程重启即丢失，仅作为抓取与标准化的过渡实现。
  - AI 分析不在本模块，由后续内部 AI 服务接口完成。

对外错误约定（由 app.main 注册的异常处理器映射为 HTTP 状态码）：
  - RSSValidationError → 400
  - RSSSourceNotFound → 404
"""
from datetime import datetime
from email.utils import parsedate_to_datetime
from hashlib import sha256
from typing import Dict, List, Optional
from urllib.parse import urlparse

import feedparser

#: 允许的资讯源协议。仅 http(s)，避免 file:// 等被当作抓取目标。
_ALLOWED_SCHEMES = ("http", "https")

#: 预置财经资讯源。让"每日要闻"开箱可用，而不必先手工登记源。
#:
#: 抓取成败取决于**服务器的出网能力**：这些域名在受限网络下可能全部超时。
#: 因此 digest 逐源返回 error，绝不把单源失败升级为整体错误——前端按
#: ok_sources / error 降级展示。需要增删源时仍走 /internal/rss/source。
DEFAULT_SOURCES: List[Dict] = [
    {
        "id": "yahoo_finance",
        "name": "Yahoo Finance",
        "url": "https://finance.yahoo.com/news/rssindex",
    },
    {
        "id": "cnbc_finance",
        "name": "CNBC Finance",
        "url": (
            "https://search.cnbc.com/rs/search/combinedcms/view.xml"
            "?partnerId=wrss01&id=100003114"
        ),
    },
    {
        "id": "marketwatch_top",
        "name": "MarketWatch",
        "url": "https://feeds.content.dowjones.io/public/rss/mw_topstories",
    },
    {
        "id": "investing_cn",
        "name": "英为财情",
        "url": "https://cn.investing.com/rss/news.rss",
    },
]


class RSSValidationError(ValueError):
    """请求载荷不合法：缺 id/url、字段为空、URL 非法等。映射为 400。"""


class RSSSourceNotFound(LookupError):
    """指定 id 的资讯源不存在。映射为 404。"""


def _clean_text(value: object) -> str:
    """把任意输入规整为去首尾空白的字符串；None / 非字符串返回空串。"""
    if value is None:
        return ""
    return str(value).strip()


def _published_timestamp(value: object) -> Optional[float]:
    """把 RSS 的时间文本解析成时间戳；解析不出来返回 None（绝不抛异常）。

    RSS 常见 RFC822（`Wed, 17 Sep 2026 08:30:00 +0800`），本模块自己写入的
    created_at 是 ISO8601，所以两条路径都要试。feedparser 对畸形日期可能让
    parsedate_to_datetime 返回 None，此时 .timestamp() 会 AttributeError，
    一并按"解析失败"处理。
    """
    text = _clean_text(value)
    if not text:
        return None
    for parser in (parsedate_to_datetime, datetime.fromisoformat):
        try:
            parsed = parser(text)
        except (TypeError, ValueError, OverflowError):
            continue
        if parsed is None:
            continue
        try:
            return parsed.timestamp()
        except (AttributeError, OverflowError, OSError):
            continue
    return None


def _article_sort_key(article: Dict) -> tuple:
    """资讯排序键：优先发布时间，解析不出来时退回入库时间。

    两类时间不能混进同一个字段比较——把"解析失败"当成很新或很旧都是错的。
    这里用元组分开比较：能解析发布时间的按发布时间排，缺发布时间的整体排在其后
    （这类条目在真实 feed 里占比很低，且用入库时间在同类内部仍然有序）。
    """
    published = _published_timestamp(article.get("published"))
    created = _published_timestamp(article.get("created_at"))
    return (published or 0.0, created or 0.0)


class RSSStore:
    """内存版资讯源仓库。

    已知限制（有意保留，不做过度设计）：
      - 无持久化：进程重启后 sources / articles 清空；
      - 无容量上限：长期运行会持续占用内存。
    两者都应由 Java 主后端接入后接管，本模块不自行发明淘汰策略。
    """

    #: 同一资讯源在 digest 中的最小抓取间隔（秒）。
    #: 前端每次打开行情页都会问一次要闻，不设间隔就会把外部 feed 打爆。
    DIGEST_MIN_INTERVAL_SECONDS = 300

    def __init__(self, seed_defaults: bool = True):
        self.sources: Dict[str, Dict] = {}
        self.articles: Dict[str, Dict] = {}
        self._last_crawled: Dict[str, datetime] = {}
        if seed_defaults:
            self._seed_default_sources()

    def _seed_default_sources(self) -> None:
        """补齐预置资讯源；**不覆盖**已登记的同 id 源（外部登记优先）。"""
        for source in DEFAULT_SOURCES:
            if source["id"] in self.sources:
                continue
            try:
                self.add_source(dict(source))
            except RSSValidationError:
                # 预置数据自身不合法时跳过即可，不该让进程起不来。
                continue

    # ---- 资讯源 ----

    def add_source(self, source: Dict) -> Dict:
        """登记/覆盖一个资讯源。

        必填 `id` 与 `url`；`name` 缺省回退为 `id`。同 id 重复登记视为覆盖更新。
        """
        if not isinstance(source, dict):
            raise RSSValidationError("source 必须是 JSON 对象")

        source_id = _clean_text(source.get("id"))
        url = _clean_text(source.get("url"))
        if not source_id:
            raise RSSValidationError("source.id 不能为空")
        if not url:
            raise RSSValidationError("source.url 不能为空")

        scheme = urlparse(url).scheme.lower()
        if scheme not in _ALLOWED_SCHEMES:
            raise RSSValidationError(
                f"source.url 仅支持 {'/'.join(_ALLOWED_SCHEMES)}，当前为 '{scheme or '空'}'"
            )

        stored = {
            "id": source_id,
            "url": url,
            "name": _clean_text(source.get("name")) or source_id,
            "enabled": bool(source.get("enabled", True)),
            "created_at": datetime.now().isoformat(),
        }
        self.sources[source_id] = stored
        return stored

    def list_sources(self) -> List[Dict]:
        return list(self.sources.values())

    def get_source(self, source_id: str) -> Dict:
        source = self.sources.get(_clean_text(source_id))
        if source is None:
            raise RSSSourceNotFound(f"资讯源不存在: {source_id}")
        return source

    # ---- 抓取 ----

    def crawl(self, source_id: str) -> Dict:
        """抓取指定资讯源并标准化其中的新文章。

        返回结构化信封，**不再用空列表同时表示「成功但无新文章」和「抓取失败」**
        ——调用方必须能区分这两种情况。

            {"source_id", "ok", "fetched", "added", "skipped", "error"}
        """
        source = self.get_source(source_id)

        # feedparser 不抛异常：网络/解析失败体现在 bozo 与 entries 上，需显式读取。
        feed = feedparser.parse(source["url"])
        entries = list(getattr(feed, "entries", []) or [])
        error = self._feed_error(feed)

        added: List[Dict] = []
        skipped = 0
        for item in entries:
            article = self._normalize(source["id"], item)
            if article is None:
                skipped += 1
                continue
            if article["id"] in self.articles:
                skipped += 1
                continue
            self.articles[article["id"]] = article
            added.append(article)

        return {
            "source_id": source["id"],
            "ok": error is None,
            "fetched": len(entries),
            "added": added,
            "skipped": skipped,
            "error": error,
        }

    @staticmethod
    def _feed_error(feed) -> Optional[str]:
        """把 feedparser 的 bozo 状态转成可读错误；正常 feed 返回 None。"""
        if not getattr(feed, "bozo", 0):
            return None
        exc = getattr(feed, "bozo_exception", None)
        if exc is None:
            detail = "未知解析错误"
        else:
            detail = _clean_text(exc) or exc.__class__.__name__
        if not getattr(feed, "entries", None):
            return f"抓取失败: {detail}"
        # 有条目但 bozo：属于「部分可解析」，给出告警而非整体失败。
        return f"部分解析告警: {detail}"

    def _normalize(self, source_id: str, item) -> Optional[Dict]:
        """把一条 feed 条目标准化为文章；信息不足到无法去重时返回 None。

        去重键只由 `标题|链接` 决定，**不含 source_id**——因此同一篇文章若被多个
        feed 转载，只会入库一次（信息聚合的有意行为，避免同一新闻重复出现）。
        代价：文章只归属于首个抓到的源，`list_articles(source_id)` 不会在其他
        转载源下列出它。若后续需要「谁转载了这篇」，应改为记录多个来源，
        而不是放宽去重键。
        """
        title = _clean_text(item.get("title"))
        url = _clean_text(item.get("link"))
        if not title and not url:
            return None

        key = sha256(f"{title}|{url}".encode("utf-8")).hexdigest()
        return {
            "id": key,
            "source_id": source_id,
            "title": title,
            "url": url,
            "summary": _clean_text(item.get("summary")),
            "published": _clean_text(item.get("published")),
            "created_at": datetime.now().isoformat(),
        }

    # ---- 文章 ----

    def list_articles(self, source_id: Optional[str] = None) -> List[Dict]:
        articles = list(self.articles.values())
        if source_id is None:
            return articles
        wanted = _clean_text(source_id)
        return [a for a in articles if a["source_id"] == wanted]

    # ---- 每日要闻 ----

    def digest(self, refresh: bool = True, force: bool = False) -> Dict:
        """刷新（受间隔限制）并返回合并后的最新资讯。

        这是"每日要闻"的唯一入口：抓取归本模块，Java 主后端只做薄代理与降级。
        返回：

            {"generated_at", "refreshed", "ok_sources", "total_sources",
             "sources": [{source_id, name, ok, crawled, fetched, added, error}],
             "articles": [...]}

        单源失败不会让整个 digest 失败：error 逐源给出，articles 仍是已抓到的部分。
        """
        now = datetime.now()
        statuses: List[Dict] = []
        for source in self.list_sources():
            source_id = source["id"]
            name = source.get("name") or source_id
            last = self._last_crawled.get(source_id)
            due = bool(refresh) and (
                force
                or last is None
                or (now - last).total_seconds() >= self.DIGEST_MIN_INTERVAL_SECONDS
            )
            if not due:
                statuses.append({
                    "source_id": source_id, "name": name, "ok": True, "crawled": False,
                    "fetched": 0, "added": 0, "error": None,
                })
                continue
            try:
                result = self.crawl(source_id)
            except (RSSSourceNotFound, RSSValidationError) as exc:
                statuses.append({
                    "source_id": source_id, "name": name, "ok": False, "crawled": False,
                    "fetched": 0, "added": 0, "error": str(exc),
                })
                continue
            self._last_crawled[source_id] = now
            statuses.append({
                "source_id": source_id, "name": name, "ok": bool(result["ok"]), "crawled": True,
                "fetched": result["fetched"], "added": len(result["added"]),
                "error": result["error"],
            })

        articles = self.list_articles()
        articles.sort(key=_article_sort_key, reverse=True)
        return {
            "generated_at": now.isoformat(),
            "refreshed": sum(1 for item in statuses if item["crawled"]),
            "ok_sources": sum(1 for item in statuses if item["ok"]),
            "total_sources": len(statuses),
            "sources": statuses,
            "articles": articles,
        }


rss_store = RSSStore()