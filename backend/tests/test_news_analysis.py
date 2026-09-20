import json

import pytest

from backend.app import ai_service


def test_news_analysis_returns_bounded_auditable_fields(monkeypatch):
    def fake_chat(messages, temperature=0.7, max_tokens=None):
        assert temperature == 0.0
        assert "只返回 JSON 数组" in messages[-1]["content"]
        return {
            "model": "test-news-model",
            "usage": {"total_tokens": 88},
            "content": json.dumps([{
                "key": "wire|https://example.com/a",
                "summary": "央行维持政策不变。",
                "keywords": ["利率", "黄金"],
                "sentiment": "positive",
                "risk_level": "medium",
                "impact_direction": "positive",
                "rationale": "政策预期保持稳定。",
                "related_markets": ["gold_etf", "macro"],
            }], ensure_ascii=False),
        }

    monkeypatch.setattr(ai_service, "_chat_request", fake_chat)
    result = ai_service.analyze_news_articles([{
        "key": "wire|https://example.com/a",
        "title": "央行政策",
        "body": "政策原文片段",
        "source": "Wire",
    }])

    assert result["model"] == "test-news-model"
    assert result["usage"]["total_tokens"] == 88
    item = result["analyses"][0]
    assert item["analysis_source"] == "model"
    assert item["sentiment"] == "positive"
    assert item["risk_level"] == "medium"
    assert item["related_markets"] == ["gold_etf", "macro"]


def test_news_analysis_rejects_unparseable_model_output(monkeypatch):
    monkeypatch.setattr(ai_service, "_chat_request", lambda *args, **kwargs: {"content": "not-json"})

    with pytest.raises(RuntimeError, match="无法解析|格式异常"):
        ai_service.analyze_news_articles([{"key": "x", "title": "headline"}])


def test_empty_news_analysis_does_not_call_provider(monkeypatch):
    called = False

    def fail(*args, **kwargs):
        nonlocal called
        called = True
        raise AssertionError("empty analysis must not call provider")

    monkeypatch.setattr(ai_service, "_chat_request", fail)
    assert ai_service.analyze_news_articles([])["analyses"] == []
    assert called is False
