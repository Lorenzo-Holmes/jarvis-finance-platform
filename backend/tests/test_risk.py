"""风险预警（FR-10）确定性计算与端点测试。

运行：在仓库根目录 `pytest backend/tests/test_risk.py -v`（或 cd backend 后 pytest tests/test_risk.py）。
"""
import pytest

from backend.app.ai_service import analyze_risk
from backend.app.research_tools import risk_metrics


def test_risk_metrics_deterministic_on_gentle_series():
    closes = [100 + i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    result = risk_metrics(closes, confidence="0.95", portfolio_value="100000", symbol="gold_etf")

    assert result["available"] is True
    assert result["bars"] == 60
    assert result["symbol"] == "gold_etf"
    assert result["confidence"] == "0.950000"
    # 温和上行序列：波动与回撤都小，VaR/ES 在 -1% 附近
    assert result["var_pct"] == "-1.1628"
    assert result["es_pct"] == "-1.2012"
    assert result["vol_annual_pct"] == "9.9889"
    assert result["max_drawdown_pct"] == "-1.2405"
    assert result["var_amount"] == "-1162.790698"
    # 未命中高风险阈值 → 低级别提示
    assert any(alert["level"] == "low" for alert in result["alerts"])
    assert not any(alert["level"] == "high" for alert in result["alerts"])


def test_risk_metrics_raises_high_alerts_on_volatile_series():
    closes = [100]
    for _ in range(59):
        closes.append(closes[-1] * (1 + [-0.05, -0.03, -0.01, 0.01, 0.03, 0.05][_ % 6]))

    result = risk_metrics(closes)

    assert result["available"] is True
    levels = {alert["level"] for alert in result["alerts"]}
    assert "high" in levels
    # VaR 达到 -5% 应触发 high；无资金规模时不得出现金额类预警
    assert any(alert["metric"] == "var" and alert["level"] == "high" for alert in result["alerts"])


def test_risk_metrics_insufficient_samples():
    result = risk_metrics([100, 101, 102])
    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"


def test_risk_metrics_rejects_invalid_closes():
    result = risk_metrics(["abc", None, "-1", 100, 101, 102, 103, 104, 105, 106, 107])
    # 只保留正数有效值 → 不足 10 根
    assert result["available"] is False


def test_analyze_risk_reports_data_insufficiency_without_llm():
    """样本不足时返回 available=False，不触发 LLM 调用。"""
    result = analyze_risk([100, 101, 102])
    assert result["available"] is False
    assert "content" not in result


def test_analyze_risk_calls_llm_with_metrics(monkeypatch):
    """样本充足时调用 LLM 生成报告，返回结构含 metrics/alerts/content。"""
    calls = {}

    def fake_chat_request(messages, temperature=0.7, max_tokens=None):
        calls["messages"] = messages
        calls["temperature"] = temperature
        return {"content": "风险报告占位", "role": "assistant", "model": "test", "usage": None}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)
    closes = [100 + i * 0.5 for i in range(60)]
    result = analyze_risk(closes, confidence=0.95, portfolio_value=100000, symbol="gold_etf")

    assert result["available"] is True
    assert result["content"]["content"] == "风险报告占位"
    assert "var_pct" in result["metrics"]
    assert isinstance(result["alerts"], list)
    assert calls["temperature"] == 0.3
    user_prompt = calls["messages"][0]["content"]
    assert "确定性计算结果" in user_prompt
    assert "单日VaR" in user_prompt  # 数值已注入 prompt
