"""智能询报价趋势区间（FR-07）确定性计算与端点测试。

运行：在仓库根目录 `pytest backend/tests/test_quote_forecast.py -v`（或 cd backend 后 pytest tests/test_quote_forecast.py）。

覆盖要点：
  - 趋势区间的确定性（同输入同输出，中心值=最近收盘价+斜率×天数）
  - 边界：样本不足、非法收盘价、预测天数与置信度裁剪
  - smart_quote 扩展后的向后兼容（无 closes 时不输出 forecast，content 语义不变）
"""
from decimal import Decimal

import pytest

from backend.app.ai_routes import QuoteReq
from backend.app.ai_service import smart_quote
from backend.app.research_tools import _z_score_for, trend_forecast


def test_trend_forecast_deterministic_on_gentle_series():
    closes = [100 + i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    result = trend_forecast(closes, horizon_days=5, confidence=0.95, symbol="gold_etf")

    assert result["available"] is True
    assert result["bars"] == 60
    assert result["symbol"] == "gold_etf"
    assert result["confidence"] == "0.950000"
    assert result["horizon_days"] == 5
    assert result["last_close"] == "130.400000"
    assert result["center"] == "132.828947"
    assert result["lower"] == "129.232805"
    assert result["upper"] == "136.425090"
    assert result["change_to_center_pct"] == "1.8627"
    assert result["slope_pct_per_day"] == "0.3725"
    assert result["band_pct"] == "2.7578"
    assert result["vol_daily_pct"] == "0.6292"


def test_trend_forecast_center_equals_last_close_plus_slope_times_horizon():
    """纯线性序列：中心值必须严格等于 最近收盘价 + 斜率 × 天数（口径可审计）。"""
    closes = [100 + i for i in range(40)]          # 每步 +1，最近收盘价 139
    result = trend_forecast(closes, horizon_days=5)

    assert result["last_close"] == "139.000000"
    assert result["center"] == "144.000000"        # 139 + 1×5
    assert result["change_to_center_pct"] == "3.5971"
    # 线性序列的日收益非恒定，但仍应有极小波动区间
    assert result["band_pct"] == "0.3600"


def test_trend_forecast_boundaries_wrap_center():
    closes = [100 + i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    result = trend_forecast(closes, horizon_days=5)

    assert float(result["lower"]) < float(result["center"]) < float(result["upper"])


def test_trend_forecast_defaults_horizon_and_confidence():
    closes = [100 + i * 0.5 for i in range(60)]
    result = trend_forecast(closes)

    assert result["horizon_days"] == 5              # 默认 5 个交易日
    assert result["confidence"] == "0.950000"       # 默认 95%


def test_trend_forecast_clamps_horizon_and_confidence():
    closes = [100 + i * 0.5 for i in range(60)]

    high = trend_forecast(closes, horizon_days=999, confidence=0.999)
    assert high["horizon_days"] == 60               # 上限裁剪
    assert high["confidence"] == "0.990000"         # 上限裁剪

    low = trend_forecast(closes, horizon_days=0, confidence=0.1)
    assert low["horizon_days"] == 1                 # 下限裁剪
    assert low["confidence"] == "0.500000"          # 下限裁剪


def test_trend_forecast_higher_confidence_widens_band():
    closes = [100 + i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    narrow = trend_forecast(closes, horizon_days=5, confidence=0.90)
    wide = trend_forecast(closes, horizon_days=5, confidence=0.99)

    assert float(wide["band_pct"]) > float(narrow["band_pct"])


def test_trend_confidence_uses_two_sided_normal_quantiles():
    assert _z_score_for(Decimal("0.90")) == Decimal("1.6449")
    assert _z_score_for(Decimal("0.95")) == Decimal("1.9600")
    assert _z_score_for(Decimal("0.99")) == Decimal("2.5758")
    assert _z_score_for(Decimal("0.50")) == Decimal("0.6745")
    # 93% must not be silently reported as 93% while using a 90% critical value.
    assert abs(float(_z_score_for(Decimal("0.93"))) - 1.8119) < 0.0001


def test_trend_forecast_longer_horizon_widens_band():
    closes = [100 + i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    short = trend_forecast(closes, horizon_days=5)
    long = trend_forecast(closes, horizon_days=20)

    assert float(long["band_pct"]) > float(short["band_pct"])


def test_trend_forecast_insufficient_samples():
    result = trend_forecast([100, 101, 102], horizon_days=5)

    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"
    assert result["bars"] == 3


def test_trend_forecast_empty_and_none_samples():
    for raw in ([], None, [None, None]):
        result = trend_forecast(raw)
        assert result["available"] is False
        assert result["reason"] == "insufficient_closes"


def test_trend_forecast_rejects_invalid_closes():
    """非正数/非法值被过滤，剩余样本不足则判定不可用。"""
    result = trend_forecast([100, 0, -5, "abc", None, 101, 102], horizon_days=5)

    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"


def test_trend_forecast_ignores_non_finite_closes():
    closes = [100 + i * 0.5 for i in range(60)] + [float("inf"), float("nan"), None, ""]
    result = trend_forecast(closes, horizon_days=5)

    assert result["available"] is True
    assert result["bars"] == 60                     # 非有限值被剔除，不影响样本


def test_trend_forecast_volatile_series_has_wide_band():
    closes = [100]
    for i in range(59):
        closes.append(closes[-1] * (1 + [-0.05, -0.03, -0.01, 0.01, 0.03, 0.05][i % 6]))
    result = trend_forecast(closes, horizon_days=10, confidence=0.95)

    assert result["available"] is True
    assert result["vol_daily_pct"] == "3.4104"
    assert result["band_pct"] == "21.1377"          # 高波动 → 区间显著变宽
    assert float(result["lower"]) > 0               # 下界不应为负价


def test_trend_forecast_downward_series_centers_below_last_close():
    closes = [200 - i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    result = trend_forecast(closes, horizon_days=5)

    assert float(result["slope_pct_per_day"]) < 0
    assert float(result["center"]) < float(result["last_close"])
    assert float(result["change_to_center_pct"]) < 0


# ---- 接口校验 ----

def test_quote_request_accepts_optional_forecast_fields():
    req = QuoteReq(price_data={"price": 100}, closes=[100, 101], horizon_days=5, confidence=0.95)
    assert req.closes == [100, 101]
    assert req.horizon_days == 5


def test_quote_request_allows_missing_closes_for_backward_compatibility():
    req = QuoteReq(price_data={"price": 100})
    assert req.closes is None
    assert req.horizon_days is None
    assert req.confidence is None


def test_quote_request_rejects_out_of_range_horizon():
    with pytest.raises(Exception):
        QuoteReq(price_data={}, horizon_days=0)
    with pytest.raises(Exception):
        QuoteReq(price_data={}, horizon_days=61)


def test_quote_request_rejects_out_of_range_confidence():
    with pytest.raises(Exception):
        QuoteReq(price_data={}, confidence=0.3)
    with pytest.raises(Exception):
        QuoteReq(price_data={}, confidence=1.0)


# ---- smart_quote 扩展 ----

def test_smart_quote_without_closes_omits_forecast(monkeypatch):
    """向后兼容：不传 closes 时不输出 forecast，返回结构保持旧语义。"""
    monkeypatch.setattr(
        "backend.app.ai_service._chat_request",
        lambda messages, temperature=0.7, max_tokens=None: {"content": "报价解读占位"},
    )
    result = smart_quote({"price": 100, "prev_close": 99})

    assert result["available"] is True
    assert result["content"]["content"] == "报价解读占位"
    assert "metrics" in result
    assert "forecast" not in result


def test_smart_quote_with_closes_adds_forecast_and_injects_prompt(monkeypatch):
    calls = {}

    def fake_chat_request(messages, temperature=0.7, max_tokens=None):
        calls["prompt"] = messages[0]["content"]
        calls["temperature"] = temperature
        return {"content": "报价解读占位"}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)
    closes = [100 + i * 0.5 for i in range(60)]
    result = smart_quote({"price": 130, "prev_close": 129}, closes=closes,
                         horizon_days=5, confidence=0.95, symbol="gold_etf")

    assert result["available"] is True
    assert result["forecast"]["available"] is True
    assert result["forecast"]["last_close"] == "129.500000"
    assert result["forecast"]["center"] == "132.000000"   # 129.5 + 0.5×5
    # 趋势区间数值已注入 prompt 供 LLM 引用
    assert "趋势区间" in calls["prompt"]
    assert result["forecast"]["center"] in calls["prompt"]
    assert calls["temperature"] == 0.5


def test_smart_quote_insufficient_closes_omits_forecast(monkeypatch):
    """样本不足时明确返回 available=false，避免前端展示空的趋势指标。"""
    called = False

    def fake_chat_request(*args, **kwargs):
        nonlocal called
        called = True
        return {"content": "不应调用模型"}

    monkeypatch.setattr(
        "backend.app.ai_service._chat_request",
        fake_chat_request,
    )
    result = smart_quote({"price": 100}, closes=[100, 101, 102])

    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"
    assert result["bars"] == 3
    assert called is False
    assert "forecast" not in result


def test_smart_quote_empty_closes_is_unavailable(monkeypatch):
    monkeypatch.setattr(
        "backend.app.ai_service._chat_request",
        lambda *args, **kwargs: {"content": "不应调用模型"},
    )
    result = smart_quote({"price": 100}, closes=[])

    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"
    assert result["bars"] == 0
