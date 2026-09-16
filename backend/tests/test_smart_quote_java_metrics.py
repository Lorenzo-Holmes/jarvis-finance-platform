"""⑧ quote 面的行为级契约：Java 下发的 metrics 被引用，本地自算不再发生。

与风险面的 test_analyze_risk_java_metrics.py 同构，但判据不同、必须各自钉住：
- analyze_risk 看 `available` 字段；quote_metrics 的结果里**没有** available，
  所以 smart_quote 用的是"非空字典"判断。这两处若照抄，引用会静默失效。
- smart_quote 还会算 trend_forecast（尚未移植）。本文件只在 closes=None 时做等价性断言，
  以免把未迁移的那部分算进来。
"""
from backend.app import ai_service

# 典型快照在两端各自独立钉住的字面量（Java 的 QuoteMetricsTest 与
# test_quote_metrics_contract.py 都断言同一组值）。
SNAPSHOT = {
    "price": 100.5, "prev_close": 100, "open": 99, "high": 102, "low": 98.5,
    "quote_time": "2026-01-02T10:00:00", "source": "tencent",
}
JAVA_SHAPE = {
    "price": "100.500000",
    "prev_close": "100.000000",
    "open": "99.000000",
    "high": "102.000000",
    "low": "98.500000",
    "quote_time": "2026-01-02T10:00:00",
    "source": "tencent",
    "change": "0.500000",
    "change_pct": "0.5000",
    "vs_open_pct": "1.5152",
    "intraday_range_pct": "3.5000",
}


def forbid_local_recompute(monkeypatch):
    """把本地自算换成"一调用就失败"，用来证明它真的没被调用。"""

    def _boom(*args, **kwargs):
        raise AssertionError("已有 Java 下发的 metrics 时不应再自算")

    monkeypatch.setattr(ai_service, "quote_metrics", _boom)


def stub_chat(monkeypatch, content="行情解读"):
    monkeypatch.setattr(ai_service, "_chat_request", lambda *args, **kwargs: content)


def test_provided_metrics_are_used_verbatim_without_recomputing(monkeypatch):
    forbid_local_recompute(monkeypatch)
    stub_chat(monkeypatch)

    result = ai_service.smart_quote(SNAPSHOT, metrics=dict(JAVA_SHAPE))

    assert result["available"] is True
    assert result["metrics"] == JAVA_SHAPE
    assert result["content"] == "行情解读"


def test_provided_metrics_are_not_mutated(monkeypatch):
    forbid_local_recompute(monkeypatch)
    stub_chat(monkeypatch)
    passed = dict(JAVA_SHAPE)
    snapshot = dict(passed)

    ai_service.smart_quote(SNAPSHOT, metrics=passed)

    assert passed == snapshot, "调用方传入的 metrics 被就地修改了"


def test_empty_metrics_falls_back_instead_of_crashing(monkeypatch):
    # 判据是"非空字典"：空 dict 视为未提供 → 回退本地。
    # 若照抄风险面的 available 判据，这里会走进引用分支并产出空 metrics。
    stub_chat(monkeypatch)

    result = ai_service.smart_quote(SNAPSHOT, metrics={})

    assert result["metrics"] == JAVA_SHAPE, "空 dict 应回退本地计算"


def test_non_dict_metrics_falls_back(monkeypatch):
    stub_chat(monkeypatch)

    result = ai_service.smart_quote(SNAPSHOT, metrics="不是字典")

    assert result["metrics"] == JAVA_SHAPE


def test_without_metrics_it_still_falls_back_to_the_local_layer(monkeypatch):
    stub_chat(monkeypatch)

    result = ai_service.smart_quote(SNAPSHOT)

    assert result["metrics"] == JAVA_SHAPE


def test_referencing_java_metrics_gives_the_identical_response(monkeypatch):
    """同快照下，指标来自 Java 还是来自本地，**最终响应必须完全一致**。"""
    stub_chat(monkeypatch)

    local = ai_service.smart_quote(SNAPSHOT)
    switched = ai_service.smart_quote(SNAPSHOT, metrics=dict(JAVA_SHAPE))

    assert switched == local


def test_metrics_survive_the_unavailable_forecast_early_return(monkeypatch):
    # closes 为空列表时 trend_forecast 不可用，函数会提前返回；
    # 这条钉住"提前返回也要带上 metrics"，以及此时同样引用 Java 下发的值。
    forbid_local_recompute(monkeypatch)

    def _no_llm(*args, **kwargs):
        raise AssertionError("趋势不可用时不应调用 LLM")

    monkeypatch.setattr(ai_service, "_chat_request", _no_llm)

    result = ai_service.smart_quote(SNAPSHOT, closes=[], metrics=dict(JAVA_SHAPE))

    assert result["available"] is False
    assert result["metrics"] == JAVA_SHAPE


def test_the_route_field_accepts_java_metrics():
    from backend.app.ai_routes import QuoteReq

    req = QuoteReq(price_data=SNAPSHOT, metrics=JAVA_SHAPE)

    assert req.metrics == JAVA_SHAPE