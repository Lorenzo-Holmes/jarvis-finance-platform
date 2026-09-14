"""市场趋势预测（FR-12）确定性计算与端点测试。

运行：在仓库根目录 `pytest backend/tests/test_market_trend.py -v`（或 cd backend 后 pytest tests/test_market_trend.py）。

覆盖要点：
  - 趋势区间的确定性（复用 trend_forecast：中心值 = 最近收盘价 + 斜率 × 天数）
  - 预测依据（SMA5/20、EMA12、RSI14、距 SMA20、支撑/阻力、均线排列）齐全且口径正确
  - 方向标签（上行 / 下行 / 横盘）
  - 边界：样本不足、非法收盘价、非有限值
  - market_trend 服务层：prompt 注入确定性结果、样本不足时不调用模型
  - 接口校验：TrendReq 字段范围
"""
import pytest

from backend.app.ai_routes import TrendReq
from backend.app.ai_service import market_trend as market_trend_service
from backend.app.research_tools import market_trend


def _linear(n=40, start=100, step=1):
    return [start + i * step for i in range(n)]


# ---- 确定性计算层 ----

def test_market_trend_deterministic_on_linear_series():
    result = market_trend(_linear(), horizon_days=5, confidence=0.95, symbol="gold_etf")

    assert result["available"] is True
    assert result["symbol"] == "gold_etf"
    assert result["bars"] == 40
    assert result["horizon_days"] == 5
    assert result["confidence"] == "0.950000"
    assert result["last_close"] == "139.000000"
    # 中心值必须严格等于 最近收盘价 + 斜率 × 天数（口径可审计）
    assert result["center"] == "144.000000"


def test_market_trend_exposes_basis_indicators():
    result = market_trend(_linear(), horizon_days=5)
    indicators = result["indicators"]

    assert indicators["sma5"] == "137.000000"        # 平均 135..139
    assert indicators["sma20"] == "129.500000"       # 平均 120..139
    assert indicators["rsi14"] == "100.0000"         # 全为上涨 → RSI 上限
    assert indicators["distance_to_sma20_pct"] == "7.3359"
    assert indicators["support20"] == "120.000000"
    assert indicators["resistance20"] == "139.000000"
    assert indicators["ma_trend"] == "多头排列"       # SMA5 > SMA20
    assert indicators["ema12"] is not None


def test_market_trend_direction_up_for_rising_series():
    result = market_trend(_linear(), horizon_days=5)
    assert result["direction"]["key"] == "up"
    assert result["direction"]["label"] == "上行趋势"
    assert float(result["slope_pct_per_day"]) > 0


def test_market_trend_direction_down_for_falling_series():
    result = market_trend(_linear(step=-1), horizon_days=5)
    assert result["direction"]["key"] == "down"
    assert float(result["center"]) < float(result["last_close"])
    assert float(result["slope_pct_per_day"]) < 0


def test_market_trend_direction_flat_for_constant_series():
    result = market_trend([100 for _ in range(40)], horizon_days=5)
    assert result["available"] is True
    assert result["direction"]["key"] == "flat"
    assert result["center"] == "100.000000"


def test_market_trend_boundaries_wrap_center():
    closes = [100 + i * 0.5 + (i % 7) * 0.3 for i in range(60)]
    result = market_trend(closes, horizon_days=10, confidence=0.95)
    assert float(result["lower"]) < float(result["center"]) < float(result["upper"])


def test_market_trend_insufficient_samples():
    result = market_trend([100, 101, 102], horizon_days=5)
    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"
    assert result["bars"] == 3


def test_market_trend_empty_and_none_samples():
    for raw in ([], None, [None, None]):
        result = market_trend(raw)
        assert result["available"] is False
        assert result["reason"] == "insufficient_closes"


def test_market_trend_rejects_invalid_closes():
    result = market_trend([100, 0, -5, "abc", None, 101, 102], horizon_days=5)
    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"


def test_market_trend_ignores_non_finite_closes():
    closes = _linear(60) + [float("inf"), float("nan"), None, ""]
    result = market_trend(closes, horizon_days=5)
    assert result["available"] is True
    assert result["bars"] == 60


# ---- 服务层（prompt 注入 / 样本不足） ----

def test_market_trend_service_returns_forecast_and_injects_prompt(monkeypatch):
    calls = {}

    def fake_chat_request(messages, temperature=0.7, max_tokens=None):
        calls["prompt"] = messages[0]["content"]
        calls["temperature"] = temperature
        return {"content": "趋势预测占位"}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)
    result = market_trend_service(_linear(60), horizon_days=5, confidence=0.95, symbol="gold_etf")

    assert result["available"] is True
    assert result["forecast"]["center"] == "164.000000"   # 末值 159 + 1×5
    assert result["forecast"]["last_close"] == "159.000000"
    assert result["indicators"]["ma_trend"] == "多头排列"
    assert result["direction"]["key"] == "up"
    assert result["content"]["content"] == "趋势预测占位"
    # 确定性结果已注入 prompt 供 LLM 引用，且不得改写
    assert "确定性计算结果" in calls["prompt"]
    assert result["forecast"]["center"] in calls["prompt"]
    assert calls["temperature"] == 0.4


def test_market_trend_service_available_false_skips_model(monkeypatch):
    called = False

    def fake_chat_request(*args, **kwargs):
        nonlocal called
        called = True
        return {"content": "不应调用模型"}

    monkeypatch.setattr("backend.app.ai_service._chat_request", fake_chat_request)
    result = market_trend_service([100, 101, 102])

    assert result["available"] is False
    assert result["reason"] == "insufficient_closes"
    assert result["bars"] == 3
    assert called is False
    assert "forecast" not in result


# ---- 接口校验 ----

def test_trend_request_accepts_optional_fields():
    req = TrendReq(closes=[100, 101], horizon_days=5, confidence=0.95, symbol="gold_etf")
    assert req.closes == [100, 101]
    assert req.horizon_days == 5
    assert req.confidence == 0.95


def test_trend_request_allows_missing_closes():
    req = TrendReq()
    assert req.closes == []
    assert req.horizon_days is None
    assert req.confidence is None


def test_trend_request_rejects_out_of_range_params():
    with pytest.raises(Exception):
        TrendReq(closes=[100], horizon_days=0)
    with pytest.raises(Exception):
        TrendReq(closes=[100], horizon_days=61)
    with pytest.raises(Exception):
        TrendReq(closes=[100], confidence=0.3)
    with pytest.raises(Exception):
        TrendReq(closes=[100], confidence=1.0)
