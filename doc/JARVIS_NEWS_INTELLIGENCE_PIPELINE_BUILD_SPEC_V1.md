# JARVIS News Intelligence Pipeline Build Spec V1

## Python RSS 层

- `backend/app/rss.py`
  - 新增 URL canonicalization。
  - 新增标题规范化、内容指纹、64-bit SimHash 与 Hamming distance。
  - RSSStore 维护 source health 统计。
  - 并发 digest 下，文章“查重 -> 合并/写入”与 source health 更新使用同一状态锁，避免多个来源同时命中同一事件时产生竞态重复。
  - 重复条目合并到事件代表文章，记录 `source_ids/source_count/duplicate_count/event_cluster_id`。
  - digest 时动态计算 freshness 与 rank score，并输出 `selection_reason`。
  - `list_articles(source_id)` 对聚合后的多来源关系保持可见。
  - 在线工作集按时间 retention + 最大事件数双重裁剪，避免长期运行无界增长。

## Java API 层

- `NewsController.daily`
  - `fromDigest(..., 0)` 先整形全候选。
  - `filterDigest(...)` 再按用户订阅筛选。
  - `NewsDigest.limitItems(...)` 最后执行 Top-K。
  - `ranking=smart/latest`：默认 smart；latest 跳过语义增强并按规范化时间排序。
- `NewsSourceService.filterDigest`
  - 同维度 OR、跨维度 AND。
  - 来源匹配支持聚合事件的 `source_ids`。
- `NewsDigest`
  - 透传 intelligence/dedup 字段。

## 测试

- Python：canonical URL、跨源证据保留、近重复、source health、score 字段与排序。
- Java：AND 订阅语义、聚合来源命中、filter-before-limit 工具链。
- 保持现有 RSS/News regression 全绿。
