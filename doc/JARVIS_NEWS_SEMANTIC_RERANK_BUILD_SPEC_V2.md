# JARVIS News Semantic Rerank Build Spec V2

## Python

- 新增 `backend/app/news_semantic.py`。
- OpenAI-compatible embeddings endpoint：`/v1/embeddings`。
- 可选 rerank endpoint 使用 `{model, query, documents, top_n}` 请求，兼容 `results/data` + `index/document_index` + `relevance_score/score` 响应。
- Embedding 与 rerank 失败全部返回 `available=false`，不得抛到 `/daily`。
- MMR 基于同批 document embeddings 计算，不新增模型调用。

## Java

- 用户订阅构造稳定 ranking query。
- 用户筛选完成后、最终 Top-K 前调用 `/internal/rss/rerank`。
- Python 返回不可用或调用失败时保持 V1 顺序。

## 资源边界

- 默认最多 rerank 60 个 V1 候选。
- 不在主 Python 服务加载模型权重。
- 模型服务可独立放在 GPU 节点，也可完全不部署。
