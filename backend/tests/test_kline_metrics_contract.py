"""⑧ 跨语言契约：K 线技术指标（kline_metrics / KlineMetrics）。

与 Java 的 KlineMetricsTest 钉**同一组独立手算的字面量**。其中递增序列的 EMA 有精确解：
种子为前 12 根均值 6.5，alpha = 2/13，递推后第 v 根恰好等于 v - 5.5，故末根为 14.5。
用它来钉住"EMA 种子取均值而非首根"——这是最容易被写错、且写错后曲线整条偏移的地方。

契约要点：
- 可用性：没有有效行 → 只有 available 与 reason 两个键（形状与正常返回不同）
- 指标 6 位小数、RSI 4 位小数；样本不足 period 时该指标为 None（键仍然保留）
- 支撑/压力位只看最近 20 根，且**没有可用 low/high 时为 None**（不是 0）
- close 不可解析的行**整行丢弃**，bars 只计有效行
"""
from backend.app.research_tools import kline_metrics

FULL_KEYS = ["available", "bars", "start", "end", "last_close", "sma5", "sma20",
             "ema12", "rsi14", "distance_to_sma20_pct", "support20", "resistance20"]


def _row(date, close, low=None, high=None):
    return {"date": date, "close": close, "low": low, "high": high}


def test_constant_series_rsi_takes_the_no_volatility_branch():
    payload = {"data": [_row("d%d" % i, 100, 95, 105) for i in range(1, 21)]}

    result = kline_metrics(payload)

    assert list(result.keys()) == FULL_KEYS
    assert result["available"] is True
    assert result["bars"] == 20
    assert result["start"] == "d1"
    assert result["end"] == "d20"
    assert result["last_close"] == "100.000000"
    assert result["sma5"] == "100.000000"
    assert result["sma20"] == "100.000000"
    assert result["ema12"] == "100.000000"
    assert result["rsi14"] == "50.0000"            # 涨跌全 0 → 平均跌幅 0 且涨幅 0 → 50
    assert result["distance_to_sma20_pct"] == "0.0000"
    assert result["support20"] == "95.000000"
    assert result["resistance20"] == "105.000000"


def test_increasing_series_ema_seed_is_the_mean():
    payload = {"data": [_row("d%d" % i, i) for i in range(1, 21)]}

    result = kline_metrics(payload)

    assert result["last_close"] == "20.000000"
    assert result["sma5"] == "18.000000"           # (16+17+18+19+20)/5
    assert result["sma20"] == "10.500000"          # 210/20
    assert result["ema12"] == "14.500000"          # 种子 6.5，递推 → v-5.5
    assert result["rsi14"] == "100.0000"           # 全是涨幅 → 平均跌幅 0 且有涨幅 → 100
    assert result["distance_to_sma20_pct"] == "90.4762"   # 90.476190… → 4 位
    assert result["support20"] is None             # 没有 low 列 → None，不是 0
    assert result["resistance20"] is None


def test_insufficient_bars_yield_none_indicators():
    payload = {"data": [_row("d1", "100", 95, 105),
                        _row("d2", "101", 96, 106),
                        _row("d3", "102", 97, 107)]}

    result = kline_metrics(payload)

    assert result["bars"] == 3
    assert result["last_close"] == "102.000000"    # 字符串按字面解析
    for key in ("sma5", "sma20", "ema12", "rsi14", "distance_to_sma20_pct"):
        assert result[key] is None
    assert result["support20"] == "95.000000"
    assert result["resistance20"] == "107.000000"


def test_invalid_rows_are_dropped_entirely():
    payload = {"data": [_row("d1", 100, 95, 105),
                        _row("d2", None, 96, 106),
                        _row("d3", "abc", 97, 107),
                        _row("d4", 102, 98, 108)]}

    result = kline_metrics(payload)

    assert result["bars"] == 2
    assert result["start"] == "d1"
    assert result["end"] == "d4"
    assert result["last_close"] == "102.000000"
    assert result["sma5"] is None                  # 有效行只有 2 根


def test_unavailable_shape_has_only_two_keys():
    assert list(kline_metrics({}).keys()) == ["available", "reason"]
    assert kline_metrics({}) == {"available": False, "reason": "no_kline_data"}
    assert kline_metrics({"data": "不是列表"}) == {"available": False, "reason": "no_kline_data"}
    assert kline_metrics({"data": [_row("d1", "abc", 1, 2)]}) == {
        "available": False, "reason": "no_kline_data"}