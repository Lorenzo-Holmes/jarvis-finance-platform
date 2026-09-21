# JARVIS News Quality Telemetry Build Spec V1

## Python

- `RSSStore.digest()` 在 V1 评分与排序后生成 `quality_metrics`：
  - article_count
  - confirmed_event_count
  - duplicate_merge_count
  - source_diversity
  - average_rank_score
  - average_content_quality_score
- 在线 RSS 工作集默认只保留 30 天，且最多 5000 个事件代表文章；可通过：
  - `RSS_ARTICLE_RETENTION_DAYS`
  - `RSS_MAX_ARTICLES`
  调整。

## Java

- `NewsDigest` 透传 rank mode 与 quality metrics。
- `NewsSourceService.filterDigest()` 记录筛选前后候选数。
- `NewsController` 记录最终 `returned_count`。

## 排序模式

- `/api/news/daily?ranking=smart`：默认 V1 Intelligence Score，认证用户在配置语义服务时继续进入 V2 rerank + MMR。
- `/api/news/daily?ranking=latest`：跳过语义 rerank，按 Python 规范化的 `recency_timestamp` 倒序。

所有遥测均不参与排序计算，不记录用户查询正文。
