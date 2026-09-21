# JARVIS News Intelligence Pipeline PRD V1

## 1. 目标

把现有 RSS 聚合从“按发布时间展示抓到的文章”升级为“高信息密度、可解释、可审计的投研资讯筛选层”。V1 不依赖新的大模型运行时，先修正数据链路与确定性质量层，为后续 Embedding / Reranker 留出稳定接口。

## 2. 产品语义

- 同一筛选维度内部使用 OR：`FT OR WSJ`、`黄金 OR 宏观`。
- 不同筛选维度之间使用 AND：`(FT OR WSJ) AND (黄金 OR 宏观)`。
- 用户筛选发生在 Top-K 截断之前，避免“先截断再筛选”漏掉用户真正订阅的新闻。
- 同一事件不删除来源证据：主列表只保留一个代表事件，但保留 `source_ids/source_count`。
- 默认排序采用可解释的 intelligence score；时间仍作为核心因子和 tie-breaker。

## 3. V1 数据质量流水线

`RSS fetch -> normalize -> canonical URL -> hard quality gate -> exact/near dedup -> event merge -> source health -> multi-factor scoring -> user filtering -> Top-K`

## 4. V1 评分因子

- Source Credibility：已有管理员配置可信度。
- Freshness：按发布时间指数衰减。
- Content Quality：标题、URL、摘要、发布时间、标签完整度。
- Cross-source Confirmation：同一事件独立来源数量。
- Novelty：重复转载越多，单篇新增信息权重越低，但 confirmation 单独奖励。
- Source Health：抓取成功率的平滑估计，新来源使用先验而不是直接判低分。

输出 `rank_score` 与 `selection_reason`，禁止黑盒不可解释排序。

## 5. V1 去重

1. URL canonicalization：移除常见 tracking 参数与 fragment，规范 host/query。
2. Exact hash：规范化标题 + canonical URL。
3. Near duplicate：规范化标题、正文摘要产生 SimHash；限定近期窗口内合并。
4. Event cluster：合并后保留代表文章和所有 `source_ids`。

## 6. 非目标

- V1 不引入 Qwen/BGE 本地权重。
- V1 不做用户行为 Learning-to-Rank。
- V1 不让 LLM 决定来源可信度或最终排序权重。

## 7. 后续 V2

候选集进入 multilingual embedding + cross-encoder reranker，增加跨语言语义事件聚类与 MMR diversity；继续复用 V1 的可解释质量特征和审计字段。

## 8. 验收

- 用户订阅命中的文章不会因全局 Top-K 提前截断而丢失。
- 来源 + 主题按跨维度 AND 工作。
- tracking URL、同标题转载与近期近重复可以合并。
- 聚合事件保留全部来源 ID 和来源数量。
- 每条文章包含可解释评分字段。
- 原 RSS 单源失败降级语义不变。
