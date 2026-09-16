#!/usr/bin/env python3
"""研究任务报告：提示词组装、结构化解析、以及跨语言的任务类型契约。"""
import json
import re
from pathlib import Path

import pytest

from backend.app import research_report


def _task(**overrides):
    task = {
        "task_type": "REPORT",
        "title": "贵州茅台研究",
        "question": "当前值得关注吗？",
        "market": "a_share",
        "symbol": "sh600519",
    }
    task.update(overrides)
    return task


# ==================== 数值口径：Java 给的数一个都不许动 ====================


def test_metrics_are_injected_verbatim():
    """Java 算好的指标必须原样出现在提示词里，且被声明为唯一可信口径。"""
    metrics = {"latest_close": 1725.5, "ma20": 1698.25, "annualized_volatility": 0.2837}

    messages = research_report.build_messages(task=_task(), metrics=metrics)
    user = messages[-1]["content"]

    # 逐字出现：不能是四舍五入、不能是重算后的近似值
    assert '"latest_close":1725.5' in user
    assert '"ma20":1698.25' in user
    assert '"annualized_volatility":0.2837' in user
    assert "唯一可信的数值口径" in user
    assert "不得自行计算、修改或编造" in user


def test_prompt_forbids_inventing_missing_values():
    """缺数据要说"数据不足"，而不是估一个看起来合理的数。"""
    user = research_report.build_messages(task=_task(), metrics={})[-1]["content"]

    assert "数据不足" in user
    assert "不要估计" in user


def test_data_gaps_are_quoted_from_the_caller_not_judged_by_the_model():
    """数据缺口由 Java 判断并传入，Python 只转述。"""
    warnings = ["未取到最新报价", "日K仅 3 根，部分指标（如20日均线）无法计算"]

    user = research_report.build_messages(task=_task(), metrics={}, warnings=warnings)[-1]["content"]

    assert "已知数据缺口" in user
    for warning in warnings:
        assert warning in user


def test_no_data_gap_section_when_there_are_none():
    user = research_report.build_messages(task=_task(), metrics={"ma5": 1.0})[-1]["content"]

    assert "已知数据缺口" not in user


def test_task_focus_differs_per_type():
    focuses = {
        task_type: research_report.build_messages(
            task=_task(task_type=task_type), metrics={})[-1]["content"]
        for task_type in research_report.TASK_TYPES
    }

    assert "市场情绪" in focuses["SENTIMENT"]
    assert "产业链" in focuses["CHAIN"]
    assert "风险" in focuses["RISK"]
    assert "趋势" in focuses["TREND"]
    assert "策略" in focuses["STRATEGY"]
    assert focuses["REPORT"] != focuses["RISK"], "不同类型的提示词不该一样"


def test_unknown_task_type_falls_back_to_report_instead_of_crashing():
    """类型不属于已知集合时退回 REPORT——接口层已有 Literal 校验，这里是纵深防御。"""
    user = research_report.build_messages(
        task=_task(task_type="SOMETHING_NEW"), metrics={})[-1]["content"]

    assert "任务类型：SOMETHING_NEW" in user
    assert research_report.TASK_TYPE_FOCUS["REPORT"] in user


def test_task_without_a_symbol_still_builds_a_prompt():
    user = research_report.build_messages(
        task=_task(market=None, symbol=None), metrics={})[-1]["content"]

    assert "标的：" not in user
    assert "研究者的问题：当前值得关注吗？" in user


def test_system_message_carries_the_trust_boundary():
    messages = research_report.build_messages(task=_task(), metrics={})

    assert messages[0]["role"] == "system"
    assert "唯一可信口径" in messages[0]["content"]
    assert "不得改写、重算或编造" in messages[0]["content"]


def test_non_serializable_metric_values_do_not_break_the_prompt():
    """指标里混进非 JSON 原生类型时降级成字符串，而不是让整个任务 500。"""
    from decimal import Decimal

    user = research_report.build_messages(
        task=_task(), metrics={"ma5": Decimal("1698.25")})[-1]["content"]

    assert "ma5" in user and "1698.25" in user


# ==================== 解析：格式不合规也不能丢内容 ====================


def test_parse_reads_a_clean_json_reply():
    reply = json.dumps({
        "summary": "偏乐观",
        "sections": [{"title": "走势", "content": "站上20日线"}],
        "risks": ["成交量偏低"],
    }, ensure_ascii=False)

    parsed = research_report.parse_report(reply)

    assert parsed["parsed"] is True
    assert parsed["summary"] == "偏乐观"
    assert parsed["sections"] == [{"title": "走势", "content": "站上20日线"}]
    assert parsed["risks"] == ["成交量偏低"]


def test_parse_strips_markdown_fences():
    reply = "```json\n{\"summary\": \"结论\", \"sections\": [], \"risks\": []}\n```"

    parsed = research_report.parse_report(reply)

    assert parsed["parsed"] is True
    assert parsed["summary"] == "结论"


def test_parse_survives_prose_around_the_json():
    reply = "好的，以下是报告：\n{\"summary\": \"结论\", \"sections\": [], \"risks\": []}\n希望有帮助。"

    assert research_report.parse_report(reply)["summary"] == "结论"


def test_parse_failure_keeps_the_raw_text_instead_of_losing_the_report():
    """格式不合规仍然是有价值的产出。判失败会让用户白等一次调用。"""
    parsed = research_report.parse_report("这是一段没有 JSON 的普通回答。")

    assert parsed["parsed"] is False
    assert parsed["summary"] == "这是一段没有 JSON 的普通回答。"
    assert parsed["sections"] == []


def test_parse_handles_empty_and_none():
    for value in (None, "", "   "):
        parsed = research_report.parse_report(value)
        assert parsed["parsed"] is False
        assert parsed["summary"] == ""


def test_bad_sections_are_dropped_individually_not_wholesale():
    reply = json.dumps({
        "summary": "结论",
        "sections": [
            {"title": "好的一节", "content": "内容"},
            "不是对象但有内容",
            {"title": "", "content": ""},
            42,
        ],
        "risks": "只给了一条风险的字符串",
    }, ensure_ascii=False)

    parsed = research_report.parse_report(reply)

    assert parsed["sections"] == [
        {"title": "好的一节", "content": "内容"},
        {"title": "", "content": "不是对象但有内容"},
    ]
    assert parsed["risks"] == ["只给了一条风险的字符串"], "字符串风险也要收下"


# ==================== 跨语言契约 ====================


def test_task_type_names_match_the_java_enum():
    """任务类型名是**跨语言协议**，必须与 Java 枚举逐字一致。

    直接用正则读 Java 源文件来比对，而不是各写一份常量列表：
    两边各写一份正是漂移发生的方式。找不到 Java 源（例如单独部署 Python 服务）
    时跳过，并说明原因——不静默通过。
    """
    java_path = (
        Path(__file__).resolve().parents[2]
        / "java-backend/src/main/java/com/jarvis/research/ai/ResearchTaskType.java"
    )
    if not java_path.exists():
        pytest.skip(f"没有相邻的 Java 源码树，跳过跨语言契约检查: {java_path}")

    source = java_path.read_text(encoding="utf-8")
    body = source.split("public enum ResearchTaskType", 1)[1]
    # 枚举常量形如 REPORT,\n SENTIMENT, —— 只取枚举体里的大写下划线标识符
    java_names = set(re.findall(r"^\s{4}([A-Z][A-Z_]{2,})\s*[,;]", body, re.MULTILINE))

    assert java_names == set(research_report.TASK_TYPES), (
        f"Python 的任务类型与 Java 枚举不一致: "
        f"仅 Java 有 {java_names - set(research_report.TASK_TYPES)}，"
        f"仅 Python 有 {set(research_report.TASK_TYPES) - java_names}"
    )


# ==================== 服务层编排 ====================


def _fake_llm(monkeypatch, reply: str):
    """替换真实的 _chat_request，并记录它收到的参数。"""
    from backend.app import ai_service

    captured = {}

    def fake_chat_request(messages, temperature=0.7, max_tokens=None):
        captured["messages"] = messages
        captured["temperature"] = temperature
        captured["max_tokens"] = max_tokens
        return {"content": reply, "role": "assistant",
                "model": "deepseek-test", "usage": {"total_tokens": 1234}}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)
    return ai_service, captured


def test_service_returns_structured_report_and_echoes_the_numbers_it_was_given(monkeypatch):
    ai_service, captured = _fake_llm(monkeypatch, json.dumps({
        "summary": "偏乐观",
        "sections": [{"title": "走势", "content": "站上20日线"}],
        "risks": ["成交量偏低"],
    }, ensure_ascii=False))

    metrics = {"latest_close": 1725.5, "ma20": 1698.25}
    warnings = ["未取到最新报价"]

    data = ai_service.research_report(
        task=_task(), metrics=metrics, quote={"price": 1725.5}, warnings=warnings)

    assert data["summary"] == "偏乐观"
    assert data["sections"] == [{"title": "走势", "content": "站上20日线"}]
    assert data["risks"] == ["成交量偏低"]
    assert data["parsed"] is True
    assert data["model"] == "deepseek-test"
    assert data["usage"] == {"total_tokens": 1234}
    assert data["task_type"] == "REPORT"

    # 原样回传：落库后能回答"这份报告当时看到的是哪些数、缺了哪些数"
    assert data["metrics_used"] == metrics
    assert data["data_gaps"] == warnings

    assert captured["messages"][-1]["content"].count("1725.5") >= 1


def test_service_uses_a_low_temperature_for_reports(monkeypatch):
    """研究报告要的是稳定而不是文采。"""
    ai_service, captured = _fake_llm(monkeypatch, '{"summary":"s","sections":[],"risks":[]}')

    ai_service.research_report(task=_task(), metrics={})

    assert captured["temperature"] == 0.3
    assert captured["max_tokens"] == 3000


def test_service_keeps_the_report_when_the_model_ignores_the_json_instruction(monkeypatch):
    ai_service, _ = _fake_llm(monkeypatch, "模型直接写了一段散文，没有 JSON。")

    data = ai_service.research_report(task=_task(), metrics={})

    assert data["parsed"] is False
    assert data["summary"] == "模型直接写了一段散文，没有 JSON。"
    assert data["sections"] == []


def test_service_defaults_missing_metrics_and_warnings(monkeypatch):
    ai_service, _ = _fake_llm(monkeypatch, '{"summary":"s","sections":[],"risks":[]}')

    data = ai_service.research_report(task=_task())

    assert data["metrics_used"] == {}
    assert data["data_gaps"] == []
    # 报价不在这里回传：它已经存在 Java 侧的 context_json 里，
    # 没必要在每条报告里再存一份 13 键的信封（模型当然看得到它，见 build_messages）
    assert "quote" not in data


def test_route_rejects_an_unknown_task_type():
    """接口层用 Literal 挡住未知类型，比让模型拿到一个没人认识的名字更好。"""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from backend.app import ai_routes

    app = FastAPI()
    app.include_router(ai_routes.router)
    app.dependency_overrides[ai_routes.require_internal_service] = lambda: None

    response = TestClient(app).post("/api/ai/research/report", json={
        "task_type": "NOT_A_TYPE", "metrics": {},
    })

    assert response.status_code == 422