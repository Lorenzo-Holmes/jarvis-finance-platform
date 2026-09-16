package com.jarvis.research.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 ⑧：市场趋势预测（趋势区间 + 技术依据 + 方向标签）。
 *
 * <p>向量仍选可独立手算的：
 * <ul>
 *   <li>递增 1..21：线性序列在回看窗口（2..21）上的最小二乘斜率**恰为 1**，
 *       故 center = 21 + 1×5 = 26 可精确写出；</li>
 *   <li>恒定 100×30：斜率 0 → 横盘，两条均线相等 → 均线粘合；</li>
 *   <li>递减 21..1：斜率 -1 → 下行，且 center = 1 - 5 = -4 必为负 → 触发下界抬升。</li>
 * </ul>
 *
 * <p>中文标签一律断言 Unicode 转义：本机默认字符集是 GBK 而 pom 未设 sourceEncoding，
 * 直接写字面量时「Java 断言 Java」无法发现编译期被改写的字符。
 * 真实字符由 Python 侧契约测试钉（它按 UTF-8 读源码，与构建编码无关）。
 */
class MarketTrendTest {

    private static List<Object> series(int from, int step, int count) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(from + step * i);
        }
        return values;
    }

    private static List<Object> constant(int value, int count) {
        return series(value, 0, count);
    }

    @Test
    @DisplayName("递增序列：多头排列 + 上行，中心值可精确算出，支撑/压力来自收盘价窗口")
    void increasingSeriesGivesBullishUp() {
        Map<String, Object> result = MarketTrend.compute(series(1, 1, 21), 5, 0.95, "gold");

        assertEquals(List.of("available", "symbol", "horizon_days", "confidence", "bars",
                "last_close", "center", "lower", "upper", "change_to_center_pct",
                "slope_pct_per_day", "band_pct", "vol_daily_pct", "indicators", "direction"),
                new ArrayList<>(result.keySet()));
        assertEquals(true, result.get("available"));
        assertEquals("gold", result.get("symbol"));
        assertEquals(21, result.get("bars"));
        assertEquals("21.000000", result.get("last_close"));
        // 窗口 2..21 上 y = x + 2 → 斜率恰为 1 → center = 21 + 1*5
        assertEquals("26.000000", result.get("center"));
        assertEquals("4.7619", result.get("slope_pct_per_day"));   // 1/21*100 = 4.761904… → 4 位

        @SuppressWarnings("unchecked")
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        assertEquals(List.of("sma5", "sma20", "ema12", "rsi14", "distance_to_sma20_pct",
                "support20", "resistance20", "ma_trend"), new ArrayList<>(indicators.keySet()));
        assertEquals("19.000000", indicators.get("sma5"));        // (17+…+21)/5 = 95/5
        assertEquals("11.500000", indicators.get("sma20"));       // (2+…+21)/20 = 230/20
        assertEquals("15.500000", indicators.get("ema12"));       // 种子 6.5，递推 → v-5.5
        assertEquals("100.0000", indicators.get("rsi14"));        // 全是涨幅
        assertEquals("82.6087", indicators.get("distance_to_sma20_pct"));  // (21-11.5)/11.5
        // 收盘价窗口 2..21 —— 不是 K 线 low/high
        assertEquals("2.000000", indicators.get("support20"));
        assertEquals("21.000000", indicators.get("resistance20"));
        assertEquals("\u591A\u5934\u6392\u5217", indicators.get("ma_trend"));

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) result.get("direction");
        assertEquals("up", direction.get("key"));
        assertEquals("\u4E0A\u884C\u8D8B\u52BF", direction.get("label"));
    }

    @Test
    @DisplayName("恒定序列：斜率 0 → 横盘，两条均线相等 → 均线粘合")
    void constantSeriesGivesFlatGlued() {
        Map<String, Object> result = MarketTrend.compute(constant(100, 30), 5, 0.95, null);

        assertEquals("0.0000", result.get("slope_pct_per_day"));
        @SuppressWarnings("unchecked")
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        assertEquals("100.000000", indicators.get("sma5"));
        assertEquals("100.000000", indicators.get("sma20"));
        assertEquals("\u5747\u7EBF\u7C98\u5408", indicators.get("ma_trend"));
        assertEquals("100.000000", indicators.get("support20"));
        assertEquals("100.000000", indicators.get("resistance20"));

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) result.get("direction");
        assertEquals("flat", direction.get("key"));
        assertEquals("\u6A2A\u76D8\u9707\u8361", direction.get("label"));
    }

    @Test
    @DisplayName("递减序列：空头排列 + 下行；中心值为负时下界被抬到最近收盘价的 1%")
    void decreasingSeriesGivesBearishDownAndFlooredLower() {
        Map<String, Object> result = MarketTrend.compute(series(21, -1, 21), 5, 0.95, null);

        assertEquals("1.000000", result.get("last_close"));
        assertEquals("-4.000000", result.get("center"));          // 1 + (-1)*5
        assertEquals("0.010000", result.get("lower"));            // 1 的 1%
        assertEquals("-100.0000", result.get("slope_pct_per_day"));  // -1/1*100

        @SuppressWarnings("unchecked")
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        assertEquals("3.000000", indicators.get("sma5"));         // (5+4+3+2+1)/5
        assertEquals("10.500000", indicators.get("sma20"));       // (20+…+1)/20
        assertEquals("\u7A7A\u5934\u6392\u5217", indicators.get("ma_trend"));

        @SuppressWarnings("unchecked")
        Map<String, Object> direction = (Map<String, Object>) result.get("direction");
        assertEquals("down", direction.get("key"));
        assertEquals("\u4E0B\u884C\u8D8B\u52BF", direction.get("label"));
    }

    @Test
    @DisplayName("趋势不可用时原样返回 forecast（只有三个键），不添 indicators/direction")
    void unavailableForecastIsReturnedUntouched() {
        Map<String, Object> result = MarketTrend.compute(constant(100, 19), 5, 0.95, null);

        assertEquals(List.of("available", "reason", "bars"), new ArrayList<>(result.keySet()));
        assertEquals(false, result.get("available"));
        assertEquals("insufficient_closes", result.get("reason"));
        assertEquals(19, result.get("bars"));
    }

    @Test
    @DisplayName("方向阈值：恰好 ±0.005 判横盘，越界才判方向；null 亦为横盘")
    void directionThresholdIsInclusiveAtTheBoundary() {
        assertEquals("flat", MarketTrend.direction(null).get("key"));
        assertEquals("flat", MarketTrend.direction(new BigDecimal("0.005")).get("key"));
        assertEquals("flat", MarketTrend.direction(new BigDecimal("-0.005")).get("key"));
        assertEquals("flat", MarketTrend.direction(BigDecimal.ZERO).get("key"));
        assertEquals("up", MarketTrend.direction(new BigDecimal("0.0051")).get("key"));
        assertEquals("down", MarketTrend.direction(new BigDecimal("-0.0051")).get("key"));
    }

    @Test
    @DisplayName("样本不足 20 根时两条均线算不出，均线排列为 null（不是空字符串）")
    void maTrendIsNullWhenMovingAveragesAreMissing() {
        // 直接调 indicators：只有 3 根，sma5/sma20 均为 null
        Map<String, Object> indicators = MarketTrend.indicators(List.of(
                BigDecimal.valueOf(1), BigDecimal.valueOf(2), BigDecimal.valueOf(3)));

        assertEquals(null, indicators.get("sma5"));
        assertEquals(null, indicators.get("sma20"));
        assertEquals(null, indicators.get("distance_to_sma20_pct"));
        assertEquals(null, indicators.get("ma_trend"));
        assertTrue(indicators.containsKey("ma_trend"), "键必须保留，只是值为 null");
    }
}