package com.jarvis.research.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 ⑧：趋势区间预测。
 *
 * <p>向量都刻意选成**可独立手算**的：恒定序列的收益率全为 0 → 样本标准差恰为 0
 * → 区间半宽为 0，于是所有输出都能精确写出，不必依赖实现的中间值。
 *
 * <p>erf/Φ/分位数另有已知真值可直接比对（这些值与任何实现无关）：
 * erf(1) = 0.8427007929497149…、Φ(1.96) = 0.9750021048517795…、
 * 标准正态 99% 分位数 = 2.3263478740408408…
 */
class TrendForecastTest {

    private static List<Object> constantCloses(int count) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(100);
        }
        return values;
    }

    @Test
    @DisplayName("恒定序列：样本标准差为 0，区间退化为一个点，所有输出可精确手算")
    void constantSeriesCollapsesTheBand() {
        Map<String, Object> result = TrendForecast.compute(constantCloses(30), 5, 0.95, "gold_etf");

        assertEquals(List.of("available", "symbol", "horizon_days", "confidence", "bars",
                "last_close", "center", "lower", "upper", "change_to_center_pct",
                "slope_pct_per_day", "band_pct", "vol_daily_pct"),
                new ArrayList<>(result.keySet()));
        assertEquals(true, result.get("available"));
        assertEquals("gold_etf", result.get("symbol"));
        assertEquals(5, result.get("horizon_days"));
        assertEquals("0.950000", result.get("confidence"));
        assertEquals(30, result.get("bars"));
        assertEquals("100.000000", result.get("last_close"));
        assertEquals("100.000000", result.get("center"));
        assertEquals("100.000000", result.get("lower"));
        assertEquals("100.000000", result.get("upper"));
        assertEquals("0.0000", result.get("change_to_center_pct"));
        assertEquals("0.0000", result.get("slope_pct_per_day"));
        assertEquals("0.0000", result.get("band_pct"));
        assertEquals("0.0000", result.get("vol_daily_pct"));
    }

    @Test
    @DisplayName("非正收盘价被过滤；过滤后仍恒定，故仍可全值断言")
    void nonPositiveClosesAreDropped() {
        List<Object> values = constantCloses(30);
        values.set(3, 0);        // 0 不是正数，应被丢弃
        values.set(9, -5);       // 负价同样丢弃

        Map<String, Object> result = TrendForecast.compute(values, 5, 0.95, null);

        assertEquals(28, result.get("bars"), "只保留正数：30 - 2");
        assertEquals(null, result.get("symbol"));
        assertEquals("100.000000", result.get("last_close"));
        assertEquals("100.000000", result.get("lower"));
        assertEquals("0.0000", result.get("vol_daily_pct"));
    }

    @Test
    @DisplayName("样本不足 20 根 → 不可用（只有三个键）")
    void insufficientBars() {
        Map<String, Object> result = TrendForecast.compute(constantCloses(19), 5, 0.95, null);

        assertEquals(List.of("available", "reason", "bars"), new ArrayList<>(result.keySet()));
        assertEquals(false, result.get("available"));
        assertEquals("insufficient_closes", result.get("reason"));
        assertEquals(19, result.get("bars"));
    }

    @Test
    @DisplayName("恰好 20 根时收益率只有 19 个，仍判不可用——两个门槛都要满足")
    void returnsGateIsSeparateFromBarsGate() {
        Map<String, Object> twenty = TrendForecast.compute(constantCloses(20), 5, 0.95, null);
        assertEquals(false, twenty.get("available"));
        assertEquals(20, twenty.get("bars"));

        Map<String, Object> twentyOne = TrendForecast.compute(constantCloses(21), 5, 0.95, null);
        assertEquals(true, twentyOne.get("available"));
        assertEquals(21, twentyOne.get("bars"));
    }

    @Test
    @DisplayName("预测天数裁剪到 [1,60]，不可解析或缺省时为 5")
    void horizonIsClamped() {
        assertEquals(1, TrendForecast.compute(constantCloses(30), 0, 0.95, null).get("horizon_days"));
        assertEquals(1, TrendForecast.compute(constantCloses(30), -3, 0.95, null).get("horizon_days"));
        assertEquals(60, TrendForecast.compute(constantCloses(30), 999, 0.95, null).get("horizon_days"));
        assertEquals(5, TrendForecast.compute(constantCloses(30), null, 0.95, null).get("horizon_days"));
        assertEquals(5, TrendForecast.compute(constantCloses(30), "abc", 0.95, null).get("horizon_days"));
        // int() 向零截断
        assertEquals(7, TrendForecast.compute(constantCloses(30), 7.9, 0.95, null).get("horizon_days"));
    }

    @Test
    @DisplayName("置信度裁剪到 [0.5,0.99]，缺省 0.95")
    void confidenceIsClamped() {
        assertEquals("0.500000", TrendForecast.compute(constantCloses(30), 5, 0.1, null).get("confidence"));
        assertEquals("0.990000", TrendForecast.compute(constantCloses(30), 5, 2.0, null).get("confidence"));
        assertEquals("0.950000", TrendForecast.compute(constantCloses(30), 5, null, null).get("confidence"));
        assertEquals("0.975000", TrendForecast.compute(constantCloses(30), 5, "0.975", null).get("confidence"));
    }

    @Test
    @DisplayName("下界跌破 0 时被抬到最近收盘价的 1%")
    void lowerBoundIsFlooredAtOnePercent() {
        // 在 100 与 10000 之间来回跳：收益率交替 +99 与 -0.99，波动极大，
        // 区间半宽必然远超中心值，因此一定会触发下限规则。
        // 21 根（奇数）且首根为 100 → 末根也是 100。
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            values.add(i % 2 == 0 ? 100 : 10000);
        }

        Map<String, Object> result = TrendForecast.compute(values, 5, 0.95, null);

        assertEquals("100.000000", result.get("last_close"));
        assertEquals("1.000000", result.get("lower"), "下界应被抬到 100 的 1%");
        BigDecimal upper = new BigDecimal((String) result.get("upper"));
        BigDecimal center = new BigDecimal((String) result.get("center"));
        assertTrue(upper.compareTo(center) > 0, "上界应大于中心值");
    }

    @Test
    @DisplayName("erf / Φ / 分位数与已知真值一致（与任何实现无关的独立值）")
    void specialFunctionsMatchKnownValues() {
        assertEquals(0.8427007929497149, TrendForecast.erf(BigDecimal.ONE).doubleValue(), 1e-12);
        assertEquals(0.9750021048517795, TrendForecast.normalCdf(new BigDecimal("1.96")).doubleValue(), 1e-12);
        assertEquals(0.6914624612740131, TrendForecast.normalCdf(new BigDecimal("0.5")).doubleValue(), 1e-12);
        // 标准正态 99% 分位数
        assertEquals(2.3263478740408408, TrendForecast.quantile(new BigDecimal("0.99")).doubleValue(), 1e-9);
        // 表格命中的置信度走常量，不应走分位数
        assertEquals("1.9600", TrendForecast.zScoreFor(new BigDecimal("0.95")).toPlainString());
        assertEquals("0.6745", TrendForecast.zScoreFor(new BigDecimal("0.5")).toPlainString());
        assertEquals("2.5758", TrendForecast.zScoreFor(new BigDecimal("0.99")).toPlainString());
        // 表外置信度：0.98 → p = 0.99 → 2.3263478740408408
        assertEquals(2.3263478740408408, TrendForecast.zScoreFor(new BigDecimal("0.98")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("样本标准差是 n-1 的样本口径，恒定序列返回 0 而不是 null")
    void standardDeviationIsSampleBased() {
        List<BigDecimal> constant = List.of(BigDecimal.valueOf(100), BigDecimal.valueOf(100));
        assertEquals(BigDecimal.ZERO, TrendForecast.stdDev(constant));
        // 1 与 3：均值 2，离差平方和 2，样本方差 2/1 = 2 → 标准差 sqrt(2)
        assertEquals(Math.sqrt(2), TrendForecast.stdDev(List.of(
                BigDecimal.ONE, BigDecimal.valueOf(3))).doubleValue(), 1e-12);
        assertEquals(null, TrendForecast.stdDev(List.of(BigDecimal.ONE)));
    }
}