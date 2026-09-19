"""市场要闻中文标题：只翻译标题，严格保持与原文逐项对应。"""
import json

import pytest
from pydantic import ValidationError

from backend.app import ai_routes, ai_service


def test_news_translation_keeps_order_and_parses_json_fence(monkeypatch):
    seen = {}

    def fake_chat(messages, temperature=0.7, max_tokens=None):
        seen["messages"] = messages
        seen["temperature"] = temperature
        return {
            "content": "~~~".replace("~", "`") + "json\n"
                       + json.dumps(["美联储释放新信号", "英伟达股价上涨"], ensure_ascii=False)
                       + "\n" + "~~~".replace("~", "`"),
            "model": "stub-model",
        }

    monkeypatch.setattr(ai_service, "_chat_request", fake_chat)

    result = ai_service.translate_news_titles([
        "Fed sends a new signal",
        "Nvidia shares rise",
    ])

    assert result["translations"] == ["美联储释放新信号", "英伟达股价上涨"]
    assert result["model"] == "stub-model"
    assert seen["temperature"] == 0.0
    assert "数组长度和输入完全一致" in seen["messages"][-1]["content"]


def test_news_translation_rejects_misaligned_model_output(monkeypatch):
    monkeypatch.setattr(
        ai_service,
        "_chat_request",
        lambda *args, **kwargs: {"content": '["只有一条"]', "model": "stub"},
    )

    with pytest.raises(RuntimeError, match="数量不匹配"):
        ai_service.translate_news_titles(["one", "two"])


def test_news_translate_request_rejects_oversized_title():
    with pytest.raises(ValidationError):
        ai_routes.NewsTranslateReq(titles=["x" * 501])


def test_news_translation_route_passes_titles(monkeypatch):
    captured = {}

    def fake_translate(titles):
        captured["titles"] = titles
        return {"translations": ["中文标题"], "model": "stub"}

    monkeypatch.setattr(ai_service, "translate_news_titles", fake_translate)

    response = ai_routes.news_translate(ai_routes.NewsTranslateReq(titles=["English title"]))

    assert captured["titles"] == ["English title"]
    assert response["data"]["translations"] == ["中文标题"]
