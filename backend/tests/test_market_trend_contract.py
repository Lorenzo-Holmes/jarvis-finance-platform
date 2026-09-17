"""⑧ 跨语言契约：市场趋势预测（market_trend / MarketTrend）。

与 Java 的 MarketTrendTest 钉同一组可独立手算的字面量。递增序列 1..21 在回看窗口
（2..21）上的最小二乘斜率**恰为 1**，故 center = 21 + 1×5 = 26 可精确写出。

两处保真细节（两个实现必须一起对）：
- 支撑/压力位取自**收盘价窗口**的 min/max，而 kline_metrics 用的是 K 线 low/high 列；
- 方向标签比较的是 slope_pct_per_day 那个**已量化到 4 位小数的字符串**回解析值，
  不是内部未量化的斜率——量化的先后会改变阈值附近的判定。

中文标签同时按**字符**与**码点**钉住：码点是与 Java 侧同一组十六进制数字
（Java 用 Unicode 转义写出，本机默认 GBK 而 pom 未设 sourceEncoding，
所以那边只能与文件编码无关地写）。两边都对同一组数字断言，连接就是数值性的，
不是"看起来一样"。
"""
from decimal import Decimal

from backend.app.research_tools import _trend_direction, _trend_indicators, market_trend

FULL_KEYS = ["available", "symbol", "horizon_days", "confidence", "bars", "last_close",
             "center", "lower", "upper", "change_to_center_pct", "slope_pct_per_day",
             "band_pct", "vol_daily_pct", "indicators", "direction"]

INDICATOR_KEYS = ["sma5", "sma20", "ema12", "rsi14", "distance_to_sma20_pct",
                  "support20", "resistance20", "ma_trend"]


def test_increasing_series_gives_bullish_up():
    closes = list(range(1, 22))          # 1..21

    result = market_trend(closes, horizon_days=5, confidence="0.95", symbol="gold")

    assert list(result.keys()) == FULL_KEYS
    assert result["available"] is True
    assert result["symbol"] == "gold"
    assert result["bars"] == 21
    assert result["last_close"] == "21.000000"
    assert result["center"] == "26.000000"                  # 斜率恰为 1
    assert result["slope_pct_per_day"] == "4.7619"          # 1/21*100 → 4 位

    indicators = result["indicators"]
    assert list(indicators.keys()) == INDICATOR_KEYS
    assert indicators["sma5"] == "19.000000"                # (17+…+21)/5
    assert indicators["sma20"] == "11.500000"               # (2+…+21)/20
    assert indicators["ema12"] == "15.500000"               # 种子 6.5，递推 → v-5.5
    assert indicators["rsi14"] == "100.0000"                # 全是涨幅
    assert indicators["distance_to_sma20_pct"] == "82.6087"
    assert indicators["support20"] == "2.000000"            # 收盘价窗口 2..21，不是 low 列
    assert indicators["resistance20"] == "21.000000"
    assert indicators["ma_trend"] == "多头排列"
    assert [hex(ord(c)) for c in indicators["ma_trend"]] == ["0x591a", "0x5934", "0x6392", "0x5217"]

    assert result["direction"] == {"key": "up", "label": "上行趋势"}
    assert [hex(ord(c)) for c in result["direction"]["label"]] == ["0x4e0a", "0x884c", "0x8d8b", "0x52bf"]


def test_constant_series_gives_flat_glued():
    result = market_trend([100] * 30, horizon_days=5, confidence="0.95")

    assert result["slope_pct_per_day"] == "0.0000"
    indicators = result["indicators"]
    assert indicators["sma5"] == "100.000000"
    assert indicators["sma20"] == "100.000000"
    assert indicators["ma_trend"] == "均线粘合"
    assert [hex(ord(c)) for c in indicators["ma_trend"]] == ["0x5747", "0x7ebf", "0x7c98", "0x5408"]
    assert result["direction"] == {"key": "flat", "label": "横盘震荡"}
    assert [hex(ord(c)) for c in result["direction"]["label"]] == ["0x6a2a", "0x76d8", "0x9707", "0x8361"]


def test_decreasing_series_gives_bearish_down_and_floored_lower():
    closes = list(range(21, 0, -1))      # 21..1

    result = market_trend(closes, horizon_days=5, confidence="0.95")

    assert result["last_close"] == "1.000000"
    assert result["center"] == "-4.000000"                  # 1 + (-1)*5
    assert result["lower"] == "0.010000"                    # 中心为负 → 抬到最近收盘价的 1%
    assert result["slope_pct_per_day"] == "-100.0000"

    indicators = result["indicators"]
    assert indicators["sma5"] == "3.000000"                 # (5+4+3+2+1)/5
    assert indicators["sma20"] == "10.500000"               # (20+…+1)/20
    assert indicators["ma_trend"] == "空头排列"
    assert [hex(ord(c)) for c in indicators["ma_trend"]] == ["0x7a7a", "0x5934", "0x6392", "0x5217"]

    assert result["direction"] == {"key": "down", "label": "下行趋势"}
    assert [hex(ord(c)) for c in result["direction"]["label"]] == ["0x4e0b", "0x884c", "0x8d8b", "0x52bf"]


def test_unavailable_forecast_is_returned_untouched():
    result = market_trend([100] * 19, horizon_days=5, confidence="0.95")

    assert list(result.keys()) == ["available", "reason", "bars"]
    assert result == {"available": False, "reason": "insufficient_closes", "bars": 19}


def test_direction_threshold_is_inclusive_at_the_boundary():
    assert _trend_direction(None) == {"key": "flat", "label": "横盘震荡"}
    assert _trend_direction(Decimal("0.005"))["key"] == "flat"
    assert _trend_direction(Decimal("-0.005"))["key"] == "flat"
    assert _trend_direction(Decimal("0"))["key"] == "flat"
    assert _trend_direction(Decimal("0.0051"))["key"] == "up"
    assert _trend_direction(Decimal("-0.0051"))["key"] == "down"


def test_ma_trend_is_none_when_moving_averages_are_missing():
    indicators = _trend_indicators([Decimal("1"), Decimal("2"), Decimal("3")])

    assert indicators["sma5"] is None
    assert indicators["sma20"] is None
    assert indicators["distance_to_sma20_pct"] is None
    assert "ma_trend" in indicators
    assert indicators["ma_trend"] is None                   # 键保留，值为 None