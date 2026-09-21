import types
import importlib

from backend.app import news_semantic


class _Response:
    def __init__(self, payload, status=200):
        self._payload = payload
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise news_semantic.requests.HTTPError(f"status={self.status_code}")

    def json(self):
        return self._payload


def _article(title, rank=70):
    return {
        "title": title,
        "summary": f"{title} 的财经摘要",
        "category": "markets",
        "tags": ["市场"],
        "rank_score": rank,
        "selection_reason": ["V1 quality"],
    }


def _enable(monkeypatch, *, rerank_url=""):
    monkeypatch.setattr(news_semantic, "NEWS_SEMANTIC_ENABLED", True)
    monkeypatch.setattr(news_semantic, "NEWS_EMBEDDING_BASE_URL", "http://embed.local/v1")
    monkeypatch.setattr(news_semantic, "NEWS_EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B")
    monkeypatch.setattr(news_semantic, "NEWS_RERANK_URL", rerank_url)
    monkeypatch.setattr(news_semantic, "NEWS_RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B")
    monkeypatch.setattr(news_semantic, "NEWS_SEMANTIC_MAX_CANDIDATES", 60)
    monkeypatch.setattr(news_semantic, "NEWS_MMR_LAMBDA", 0.72)


def test_disabled_semantic_layer_is_zero_cost_and_preserves_items(monkeypatch):
    monkeypatch.setattr(news_semantic, "NEWS_SEMANTIC_ENABLED", False)
    monkeypatch.setattr(news_semantic, "NEWS_EMBEDDING_BASE_URL", "")
    called = False

    def fail(*args, **kwargs):
        nonlocal called
        called = True
        raise AssertionError("disabled semantic layer must not call provider")

    monkeypatch.setattr(news_semantic.requests, "post", fail)
    articles = [_article("A")]
    result = news_semantic.rerank_articles("黄金市场", articles)

    assert result["available"] is False
    assert result["reason"] == "semantic_disabled"
    assert result["items"] == articles
    assert called is False


def test_embedding_relevance_adds_auditable_scores_and_mmr_stage(monkeypatch):
    _enable(monkeypatch)

    def fake_post(url, **kwargs):
        assert url == "http://embed.local/v1/embeddings"
        assert kwargs["json"]["model"] == "Qwen/Qwen3-Embedding-0.6B"
        vectors = [
            [1.0, 0.0],
            [1.0, 0.0],
            [0.8, 0.6],
            [0.0, 1.0],
        ]
        return _Response({"data": [
            {"index": index, "embedding": vector}
            for index, vector in enumerate(vectors)
        ]})

    monkeypatch.setattr(news_semantic.requests, "post", fake_post)
    result = news_semantic.rerank_articles(
        "黄金市场",
        [_article("黄金上涨", 80), _article("贵金属交易", 70), _article("无关公司新闻", 90)],
    )

    assert result["available"] is True
    assert result["ranking_stage"] == "embedding_mmr"
    assert result["items"][0]["title"] == "黄金上涨"
    assert result["items"][0]["semantic_score"] == 100.0
    assert result["items"][0]["semantic_model"] == "Qwen/Qwen3-Embedding-0.6B"
    assert result["items"][0]["ranking_stage"] == "embedding_mmr"
    assert any("语义相关度" in reason for reason in result["items"][0]["selection_reason"])


def test_optional_cross_encoder_rerank_can_override_embedding_order(monkeypatch):
    _enable(monkeypatch, rerank_url="http://rerank.local/rerank")

    def fake_post(url, **kwargs):
        if url.endswith("/embeddings"):
            vectors = [[1.0, 0.0], [1.0, 0.0], [0.9, 0.1]]
            return _Response({"data": [
                {"index": index, "embedding": vector}
                for index, vector in enumerate(vectors)
            ]})
        assert url == "http://rerank.local/rerank"
        return _Response({"results": [
            {"index": 0, "relevance_score": 0.10},
            {"index": 1, "relevance_score": 0.99},
        ]})

    monkeypatch.setattr(news_semantic.requests, "post", fake_post)
    result = news_semantic.rerank_articles(
        "央行利率",
        [_article("一般市场消息", 60), _article("央行维持利率", 60)],
    )

    assert result["available"] is True
    assert result["ranking_stage"] == "rerank_mmr"
    assert result["items"][0]["title"] == "央行维持利率"
    assert result["items"][0]["rerank_score"] == 99.0
    assert result["items"][0]["rerank_model"] == "Qwen/Qwen3-Reranker-0.6B"


def test_provider_failure_returns_original_candidates_for_v1_fallback(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setattr(
        news_semantic.requests,
        "post",
        lambda *args, **kwargs: (_ for _ in ()).throw(news_semantic.requests.Timeout("timeout")),
    )
    articles = [_article("A"), _article("B")]

    result = news_semantic.rerank_articles("市场", articles)

    assert result["available"] is False
    assert result["reason"] == "semantic_provider_error"
    assert result["items"] == articles


def test_invalid_numeric_environment_does_not_break_module_import(monkeypatch):
    monkeypatch.setenv("NEWS_SEMANTIC_TIMEOUT_SECONDS", "not-a-number")
    monkeypatch.setenv("NEWS_SEMANTIC_MAX_CANDIDATES", "bad")
    monkeypatch.setenv("NEWS_MMR_LAMBDA", "oops")

    reloaded = importlib.reload(news_semantic)

    assert reloaded.NEWS_SEMANTIC_TIMEOUT == 12.0
    assert reloaded.NEWS_SEMANTIC_MAX_CANDIDATES == 60
    assert reloaded.NEWS_MMR_LAMBDA == 0.72
