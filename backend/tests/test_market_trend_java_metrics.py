"""⑧ trend 面三层契约：路由收字段 / 不再本地自算 / 换来源响应完全一致。

与风险面（test_analyze_risk_java_metrics.py）和报价面（test_smart_quote_java_metrics.py）同构。

等价性向量选**恒定序列** [100]*30：收益率全为 0 → 样本标准差恰为 0 → 区间半宽为 0，
于是 Java 侧那份字典的每个字段都能独立手算（数值来自 trend_forecast 与 market_trend
两份契约里已经钉过的字面量），不需要回抄本地实现输出。

判据说明：market_trend 的结果里**总有** available（不可用时也有），所以用 available
是否存在来判断是否引用；报价面那边 quote_metrics 没有 available，用的是非空字典判断。
"""
import pytest

from backend.app import ai_service

CONSTANT = [100] * 30

JAVA_TREND = {
    "available": True,
    "symbol": None,
    "horizon_days": 5,
    "confidence": "0.950000",
    "bars": 30,
    "last_close": "100.000000",
    "center": "100.000000",
    "lower": "100.000000",
    "upper": "100.000000",
    "change_to_center_pct": "0.0000",
    "slope_pct_per_day": "0.0000",
    "band_pct": "0.0000",
    "vol_daily_pct": "0.0000",
    "indicators": {
        "sma5": "100.000000", "sma20": "100.000000", "ema12": "100.000000",
        "rsi14": "50.0000", "distance_to_sma20_pct": "0.0000",
        "support20": "100.000000", "resistance20": "100.000000",
        "ma_trend": "均线粘合",
    },
    "direction": {"key": "flat", "label": "横盘震荡"},
}

JAVA_UNAVAILABLE = {"available": False, "reason": "insufficient_closes", "bars": 19}


def forbid_local_recompute(monkeypatch):
    def _boom(*args, **kwargs):
        raise AssertionError("已有 Java 下发的趋势结果时不应再自算")

    monkeypatch.setattr(ai_service, "trend_metrics", _boom)


def stub_chat(monkeypatch, content="趋势解读"):
    monkeypatch.setattr(ai_service, "_chat_request", lambda *args, **kwargs: content)


def test_provided_metrics_are_used_verbatim_without_recomputing(monkeypatch):
    forbid_local_recompute(monkeypatch)
    stub_chat(monkeypatch)

    result = ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95,
                                     metrics=dict(JAVA_TREND))

    assert result["available"] is True
    assert result["forecast"]["center"] == "100.000000"
    assert result["forecast"]["bars"] == 30
    assert result["forecast"]["confidence"] == "0.950000"
    assert result["indicators"]["ma_trend"] == "均线粘合"
    assert result["indicators"]["rsi14"] == "50.0000"
    assert result["direction"] == {"key": "flat", "label": "横盘震荡"}
    assert result["content"] == "趋势解读"


def test_provided_metrics_are_not_mutated(monkeypatch):
    forbid_local_recompute(monkeypatch)
    stub_chat(monkeypatch)
    passed = {**JAVA_TREND, "indicators": dict(JAVA_TREND["indicators"])}
    before = {**passed, "indicators": dict(passed["indicators"])}

    ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95, metrics=passed)

    assert passed == before, "调用方传入的 metrics 被就地修改了"


def test_provided_unavailable_short_circuits_without_recomputing(monkeypatch):
    forbid_local_recompute(monkeypatch)

    def _no_llm(*args, **kwargs):
        raise AssertionError("趋势不可用时不应调用 LLM")

    monkeypatch.setattr(ai_service, "_chat_request", _no_llm)

    result = ai_service.market_trend(CONSTANT, metrics=dict(JAVA_UNAVAILABLE))

    assert result == {"available": False, "reason": "insufficient_closes", "bars": 19}


def test_metrics_without_available_fall_back_to_local(monkeypatch):
    # 判据是 available 是否存在：空字典与无 available 的字典都算未提供 → 回退本地
    stub_chat(monkeypatch)

    for wrong in ({}, {"foo": 1}):
        result = ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95, metrics=wrong)
        assert result["forecast"]["center"] == "100.000000"


def test_without_metrics_it_still_computes_locally(monkeypatch):
    stub_chat(monkeypatch)

    result = ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95)

    assert result["forecast"]["center"] == "100.000000"
    assert result["indicators"]["ma_trend"] == "均线粘合"


def test_referencing_java_metrics_gives_the_identical_response(monkeypatch):
    stub_chat(monkeypatch)

    local = ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95)
    switched = ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95,
                                       metrics=dict(JAVA_TREND))

    assert switched == local


def test_the_route_field_accepts_java_metrics():
    from backend.app.ai_routes import TrendReq

    req = TrendReq(closes=CONSTANT, metrics=JAVA_TREND)

    assert req.metrics == JAVA_TREND
    assert req.closes == CONSTANT


@pytest.mark.parametrize("bad", [None, "字符串", 123, [], object()])
def test_non_dict_metrics_fall_back(monkeypatch, bad):
    stub_chat(monkeypatch)

    result = ai_service.market_trend(CONSTANT, horizon_days=5, confidence=0.95, metrics=bad)

    assert result["forecast"]["center"] == "100.000000"