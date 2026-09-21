# JARVIS News Semantic Rerank PRD V2

## 目标

在 V1 确定性 Intelligence Score 之后增加可选语义层，用于跨语言相关性排序与结果多样化。语义层不得成为 RSS 主链路单点故障。

## 默认模型建议

- Embedding：`Qwen/Qwen3-Embedding-0.6B`
- Reranker：`Qwen/Qwen3-Reranker-0.6B`

模型服务与 JARVIS Python 服务解耦；JARVIS 只通过 HTTP 使用模型，不直接引入 torch/vLLM 运行依赖。

## 流水线

`V1 Top candidates -> Embedding relevance -> optional Cross-Encoder rerank -> Hybrid score -> MMR diversity -> Top-K`

## 降级

- `NEWS_SEMANTIC_ENABLED=false`：原样返回 V1。
- Embedding/Rerank 服务超时、格式异常：原样返回 V1。
- Rerank 未配置：Embedding + MMR 仍工作。
- `ranking=latest`：完全跳过语义层，保持传统时间流。

## 排序

- semantic relevance：query 与 article embedding cosine。
- rerank relevance：若配置 cross-encoder，72% rerank + 28% embedding。
- hybrid：60% semantic relevance + 40% V1 deterministic rank。
- MMR：默认 lambda=0.72，在高相关与去冗余之间平衡。

## 配置

- `NEWS_SEMANTIC_ENABLED`
- `NEWS_EMBEDDING_BASE_URL`
- `NEWS_EMBEDDING_MODEL`
- `NEWS_RERANK_URL`
- `NEWS_RERANK_MODEL`
- `NEWS_SEMANTIC_API_KEY`
- `NEWS_SEMANTIC_TIMEOUT_SECONDS`
- `NEWS_SEMANTIC_MAX_CANDIDATES`
- `NEWS_MMR_LAMBDA`
