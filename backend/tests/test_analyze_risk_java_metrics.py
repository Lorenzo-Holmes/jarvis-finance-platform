"""⑧ 切换的行为级契约：Java 下发的 metrics 被引用，本地自算不再发生。

与 test_risk_metrics_contract.py 的分工：
- 那份钉的是**数学**：同一组向量下 Java 与 Python 算出的字符串逐字一致
- 这份钉的是**接线**：指标从哪里来。数学一致不代表接线正确——两件事需要各自的测试

这里用"一调用就失败"的替身来证明本地自算**真的没被调用**，而不是靠"看起来没调"。
"""
import pytest

from backend.app import ai_service

# 一份"Java 算好下发"的指标：数值本身不重要（数学一致性由跨语言契约保证），
# 重要的是它必须被**逐字引用**、且不触发本地重算。
JAVA_METRICS = {
    "available": True,
    "symbol": "gold_etf",
    "confidence": "0.950000",
    "bars": 60,
    "last_close": "518.850000",
    "var_pct": "-1.2345",
    "es_pct": "-1.9876",
    "vol_annual_pct": "12.3456",
    "max_drawdown_pct": "-8.7654",
    "alerts": [{"level": "low", "metric": "overall", "rule": "无阈值命中",
                "message": "当前样本未命中高风险阈值，维持常规监控。"}],
}


def forbid_local_recompute(monkeypatch):
    """把本地自算换成"一调用就失败"，用来证明它没被调用。"""

    def _boom(*args, **kwargs):
        raise AssertionError("已有 Java 下发的 metrics 时不应再自算")

    monkeypatch.setattr(ai_service, "risk_metrics", _boom)


def stub_chat(monkeypatch, content="风险报告"):
    # LLM 只负责写文字，这里换成固定返回，测试不联网
    monkeypatch.setattr(ai_service, "_chat_request", lambda *args, **kwargs: content)


def test_provided_metrics_are_used_verbatim_without_recomputing(monkeypatch):
    forbid_local_recompute(monkeypatch)
    stub_chat(monkeypatch)

    result = ai_service.analyze_risk([100.0] * 60, confidence=0.95,
                                     symbol="gold_etf", metrics=JAVA_METRICS)

    assert result["available"] is True
    # 数值逐字来自 Java 那一份；alerts 单独成字段，与既有响应形状一致
    assert result["metrics"] == {key: value for key, value in JAVA_METRICS.items() if key != "alerts"}
    assert result["alerts"] == JAVA_METRICS["alerts"]
    assert result["content"] == "风险报告"


def test_provided_metrics_are_not_mutated(monkeypatch):
    # 既有实现会对 alerts 做 pop（改自己的局部变量没问题）。改成引用外部传入的 dict 后，
    # 这个 pop 绝不能改到调用方的对象——同一份 metrics 可能被复用。
    forbid_local_recompute(monkeypatch)
    stub_chat(monkeypatch)
    snapshot = dict(JAVA_METRICS)

    ai_service.analyze_risk([100.0] * 60, metrics=JAVA_METRICS)

    assert JAVA_METRICS == snapshot, "调用方传入的 metrics 被就地修改了"


def test_unavailable_metrics_short_circuit_before_the_llm(monkeypatch):
    forbid_local_recompute(monkeypatch)

    def _no_llm(*args, **kwargs):
        raise AssertionError("样本不足时不应调用 LLM")

    monkeypatch.setattr(ai_service, "_chat_request", _no_llm)

    result = ai_service.analyze_risk(
        [100.0] * 60,
        metrics={"available": False, "reason": "insufficient_closes", "bars": 10})

    assert result == {"available": False, "reason": "insufficient_closes", "bars": 10}


def test_without_metrics_it_still_falls_back_to_the_local_layer(monkeypatch):
    # 老调用方、或服务端取数失败时 Java 原样转发（不带 metrics）：必须仍能工作。
    # 用翻倍序列——本地口径下 VaR/ES 恰好是 100.0000%（见跨语言契约里的推导）。
    stub_chat(monkeypatch)
    closes = [100.0 * (2 ** i) for i in range(11)]

    result = ai_service.analyze_risk(closes, confidence=0.95)

    assert result["available"] is True
    assert result["metrics"]["var_pct"] == "100.0000"
    assert result["metrics"]["es_pct"] == "100.0000"
    assert result["alerts"][0]["metric"] == "var"


def test_a_non_dict_metrics_falls_back_instead_of_crashing(monkeypatch):
    # 请求体是外部输入：类型不对时应该回退，而不是 500
    stub_chat(monkeypatch)
    closes = [100.0 * (2 ** i) for i in range(11)]

    result = ai_service.analyze_risk(closes, metrics="不是字典")

    assert result["available"] is True
    assert result["metrics"]["var_pct"] == "100.0000"


@pytest.mark.parametrize("payload", [
    {"available": False, "reason": "insufficient_closes", "bars": 10},
    {"available": True, "bars": 11, "var_pct": "0.5000"},
])
def test_the_route_field_accepts_java_metrics(monkeypatch, payload):
    # 路由层的模型：Java 会把这个字段放进请求体，必须能被接收（不能 422）
    from backend.app.ai_routes import RiskReq

    req = RiskReq(closes=[100.0] * 12, metrics=payload)

    assert req.metrics == payload


# Java 侧对**翻倍序列**算出的那份指标（形状与取值）。
# 这些字符串不是我编的：Java 的 RiskMetricsTest 与
# test_risk_metrics_contract.py 各自独立地钉住了同一组值（两端逐字一致）。
JAVA_SHAPE_FOR_DOUBLING = {
    "available": True,
    "symbol": "gold_etf",
    "confidence": "0.950000",
    "bars": 11,
    "last_close": "102400.000000",
    "var_pct": "100.0000",
    "es_pct": "100.0000",
    "vol_annual_pct": "0.0000",
    "max_drawdown_pct": "0.0000",
    "alerts": [
        {"level": "high", "metric": "var", "rule": "单日VaR绝对值 >= 4%",
         "message": "单日最大预期亏损约 100.0000%，风险敞口偏高。"},
        {"level": "high", "metric": "es", "rule": "尾部风险ES绝对值 >= 5%",
         "message": "极端情形平均亏损约 100.0000%，尾部风险显著。"},
    ],
}


def test_referencing_java_metrics_gives_the_identical_response(monkeypatch):
    """同向量同参数下，指标来自 Java 还是来自本地，**最终响应必须完全一致**。

    这才是"统一口径"可验证的含义：换个来源不该改变任何一个字节。
    如果 Python 的本地口径哪天漂移了，这条断言会立刻失败——而不是等它在页面上
    被某个人偶然发现。
    """
    stub_chat(monkeypatch)
    closes = [100.0 * (2 ** i) for i in range(11)]

    local = ai_service.analyze_risk(closes, confidence=0.95, symbol="gold_etf")
    switched = ai_service.analyze_risk(closes, confidence=0.95, symbol="gold_etf",
                                       metrics=dict(JAVA_SHAPE_FOR_DOUBLING))

    assert switched == local