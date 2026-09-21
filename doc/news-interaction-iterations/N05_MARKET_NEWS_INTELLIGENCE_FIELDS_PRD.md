# N05 需求：市场要闻保留智能排序字段

问题：`marketBoard.normalizeNewsItem()` 会丢弃后端的 intelligence/rerank 字段，导致市场首页无法展示评分与跨源确认信息。

目标：纯前端透传相关字段，不改变排序结果和链接安全逻辑。
