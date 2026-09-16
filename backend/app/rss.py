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
from hashlib import sha256
from typing import Dict, List, Optional
from urllib.parse import urlparse

import feedparser

#: 允许的资讯源协议。仅 http(s)，避免 file:// 等被当作抓取目标。
_ALLOWED_SCHEMES = ("http", "https")


class RSSValidationError(ValueError):
    """请求载荷不合法：缺 id/url、字段为空、URL 非法等。映射为 400。"""


class RSSSourceNotFound(LookupError):
    """指定 id 的资讯源不存在。映射为 404。"""


def _clean_text(value: object) -> str:
    """把任意输入规整为去首尾空白的字符串；None / 非字符串返回空串。"""
    if value is None:
        return ""
    return str(value).strip()


class RSSStore:
    """内存版资讯源仓库。

    已知限制（有意保留，不做过度设计）：
      - 无持久化：进程重启后 sources / articles 清空；
      - 无容量上限：长期运行会持续占用内存。
    两者都应由 Java 主后端接入后接管，本模块不自行发明淘汰策略。
    """

    def __init__(self):
        self.sources: Dict[str, Dict] = {}
        self.articles: Dict[str, Dict] = {}

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


rss_store = RSSStore()