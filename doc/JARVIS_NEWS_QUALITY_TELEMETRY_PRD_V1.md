# JARVIS News Quality Telemetry PRD V1

## 目标

给 News Intelligence Pipeline 增加可审计质量指标，后续调 ranking 权重、去重阈值和语义模型时有客观依据，而不是只看页面主观效果。

## 指标

- `article_count`：当前事件代表文章数。
- `confirmed_event_count`：被至少两个独立来源确认的事件数。
- `duplicate_merge_count`：被折叠进事件簇的重复/转载条数。
- `source_diversity`：当前结果覆盖的独立来源数量。
- `average_rank_score`：V1 Intelligence Score 平均值。
- `average_content_quality_score`：字段完整度平均值。
- `filter_before_count / filter_after_count`：用户订阅筛选前后候选数。
- `returned_count`：最终 Top-K 数量。

## 原则

- 指标只描述流水线，不影响排序本身。
- 不记录用户正文、查询文本等敏感内容。
- 指标缺失时前端继续正常展示。
