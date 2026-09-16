"""⑧ 跨语言契约：趋势区间预测（trend_forecast / TrendForecast）。

与 Java 的 TrendForecastTest 钉**同一组可独立手算的字面量**。恒定序列的收益率全为 0，
样本标准差恰为 0，区间半宽为 0，因此全部输出都能精确写出，不依赖实现的中间值。

关于 z 分位数（两端各自的验证方式，值得说明）：
Python 先查可审计常量表，表外才调用 statistics.NormalDist().inv_cdf；Java 侧同样先查表，
表外改用 erf 幂级数加二分求分位数（刻意不默写 CPython 的 AS241 系数）。两边的实现路径
不同，所以**不能**用"比对两边输出"来验证表外路径——那需要先有独立期望值。
本文件与 Java 测试各自对**同一个与实现无关的数学真值**（标准正态 99% 分位数
= 2.3263478740408408）断言到 1e-9。两端各自贴合数学真值，即传递性地互相一致，
而且不可能共享同一个错误假设。
"""
import math
from decimal import Decimal

from backend.app.research_tools import _std_dev, _z_score_for, trend_forecast

FULL_KEYS = ["available", "symbol", "horizon_days", "confidence", "bars", "last_close",
             "center", "lower", "upper", "change_to_center_pct", "slope_pct_per_day",
             "band_pct", "vol_daily_pct"]

# 标准正态 99% 分位数（数学真值，与任何实现无关）
Z_99 = 2.3263478740408408


def test_constant_series_collapses_the_band():
    result = trend_forecast([100] * 30, horizon_days=5, confidence="0.95", symbol="gold_etf")

    assert list(result.keys()) == FULL_KEYS
    assert result["available"] is True
    assert result["symbol"] == "gold_etf"
    assert result["horizon_days"] == 5
    assert result["confidence"] == "0.950000"
    assert result["bars"] == 30
    assert result["last_close"] == "100.000000"
    assert result["center"] == "100.000000"
    assert result["lower"] == "100.000000"
    assert result["upper"] == "100.000000"
    assert result["change_to_center_pct"] == "0.0000"
    assert result["slope_pct_per_day"] == "0.0000"
    assert result["band_pct"] == "0.0000"
    assert result["vol_daily_pct"] == "0.0000"


def test_non_positive_closes_are_dropped():
    values = [100] * 30
    values[3] = 0
    values[9] = -5

    result = trend_forecast(values, horizon_days=5, confidence="0.95")

    assert result["bars"] == 28                      # 只保留正数
    assert result["symbol"] is None
    assert result["last_close"] == "100.000000"
    assert result["lower"] == "100.000000"
    assert result["vol_daily_pct"] == "0.0000"


def test_insufficient_bars_shape():
    result = trend_forecast([100] * 19)

    assert list(result.keys()) == ["available", "reason", "bars"]
    assert result == {"available": False, "reason": "insufficient_closes", "bars": 19}


def test_returns_gate_is_separate_from_bars_gate():
    twenty = trend_forecast([100] * 20)
    assert twenty["available"] is False              # 20 根 → 只有 19 个收益率
    assert twenty["bars"] == 20

    twenty_one = trend_forecast([100] * 21)
    assert twenty_one["available"] is True
    assert twenty_one["bars"] == 21


def test_horizon_is_clamped():
    assert trend_forecast([100] * 30, horizon_days=0)["horizon_days"] == 1
    assert trend_forecast([100] * 30, horizon_days=-3)["horizon_days"] == 1
    assert trend_forecast([100] * 30, horizon_days=999)["horizon_days"] == 60
    assert trend_forecast([100] * 30, horizon_days=None)["horizon_days"] == 5
    assert trend_forecast([100] * 30, horizon_days="abc")["horizon_days"] == 5
    assert trend_forecast([100] * 30, horizon_days=7.9)["horizon_days"] == 7   # int() 向零截断


def test_confidence_is_clamped():
    assert trend_forecast([100] * 30, confidence=0.1)["confidence"] == "0.500000"
    assert trend_forecast([100] * 30, confidence=2.0)["confidence"] == "0.990000"
    assert trend_forecast([100] * 30, confidence=None)["confidence"] == "0.950000"
    assert trend_forecast([100] * 30, confidence="0.975")["confidence"] == "0.975000"


def test_lower_bound_is_floored_at_one_percent():
    # 在 100 与 10000 之间来回跳：收益率交替 +99 与 -0.99，波动极大，
    # 半宽必然远超中心值，因此一定触发下限规则。21 根（奇数）且首根为 100 → 末根也是 100。
    values = [100 if i % 2 == 0 else 10000 for i in range(21)]

    result = trend_forecast(values, horizon_days=5, confidence="0.95")

    assert result["last_close"] == "100.000000"
    assert result["lower"] == "1.000000"
    assert Decimal(result["upper"]) > Decimal(result["center"])


def test_z_score_table_hits_do_not_use_the_quantile_path():
    table = {"0.99": "2.5758", "0.975": "2.2414", "0.95": "1.9600",
             "0.90": "1.6449", "0.80": "1.2816", "0.50": "0.6745"}
    for confidence, z in table.items():
        assert _z_score_for(Decimal(confidence)) == Decimal(z)


def test_offset_confidence_matches_the_mathematical_quantile():
    # 0.98 → p = (1 + 0.98)/2 = 0.99 → 99% 分位数
    assert abs(float(_z_score_for(Decimal("0.98"))) - Z_99) < 1e-9
    assert abs(float(_z_score_for(Decimal("0.85"))) - 1.4395314709384563) < 1e-9


def test_standard_deviation_is_sample_based():
    assert _std_dev([Decimal("100"), Decimal("100")]) == Decimal("0")   # 方差 0 → 0，不是 None
    # 1 与 3：均值 2，离差平方和 2，样本方差 2/1 = 2 → sqrt(2)
    assert abs(float(_std_dev([Decimal("1"), Decimal("3")])) - math.sqrt(2)) < 1e-12
    assert _std_dev([Decimal("1")]) is None