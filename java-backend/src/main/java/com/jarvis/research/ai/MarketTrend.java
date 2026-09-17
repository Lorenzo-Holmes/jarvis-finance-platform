package com.jarvis.research.ai;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 市场趋势预测（Phase 2 ⑧ 统一口径）：单资产日 K 统计基线 + 可展示的技术依据。
 *
 * <p>逐字对应 Python 的 {@code research_tools.market_trend}：先取 {@link TrendForecast}
 * 的趋势区间，可用时再补 indicators 与 direction；**不可用时把 forecast 原样返回**
 * ——那是只有 available/reason/bars 三个键的另一种形状，不要在这里给它加键。
 *
 * <p>两处容易搞错、已按 Python 原样保留的细节：
 * <ul>
 *   <li>支撑/压力位取自**收盘价窗口**（closes 的 min/max），不是 K 线的 low/high——
 *       {@link KlineMetrics} 用的才是 low/high，两者不可互换；</li>
 *   <li>方向标签比较的是 {@code slope_pct_per_day} 那个**已经量化到 4 位小数的字符串**
 *       回解析出来的数值，不是内部未量化的斜率：量化的先后会改变阈值附近的判定。</li>
 * </ul>
 *
 * <p><b>中文标签写成 Unicode 转义，不直接写字面量</b>：pom 没有设置 sourceEncoding，
 * 本机默认字符集是 GBK，源码文件编码与构建默认编码一旦不一致，字面量会在编译期被
 * 静默改写；而「Java 断言 Java 字面量」的测试即使两端同时被改写也照样通过，
 * 证明不了字符正确。转义写法与文件编码无关，真实字符再由跨语言契约测试钉一遍。
 */
public final class MarketTrend {

    /** 支撑 / 阻力回看窗口（交易日）。 */
    private static final int INDICATOR_WINDOW = 20;

    /** 方向判定阈值（日均斜率百分比）。 */
    private static final BigDecimal DIRECTION_THRESHOLD = new BigDecimal("0.005");

    private static final String MA_BULLISH = "\u591A\u5934\u6392\u5217";      // 多头排列
    private static final String MA_BEARISH = "\u7A7A\u5934\u6392\u5217";      // 空头排列
    private static final String MA_FLAT = "\u5747\u7EBF\u7C98\u5408";         // 均线粘合
    private static final String DIRECTION_UP = "\u4E0A\u884C\u8D8B\u52BF";    // 上行趋势
    private static final String DIRECTION_DOWN = "\u4E0B\u884C\u8D8B\u52BF";  // 下行趋势
    private static final String DIRECTION_FLAT = "\u6A2A\u76D8\u9707\u8361";  // 横盘震荡

    private MarketTrend() {
    }

    public static Map<String, Object> compute(Object closesRaw, Object horizonDays,
                                              Object confidence, Object symbol) {
        Map<String, Object> forecast = TrendForecast.compute(closesRaw, horizonDays, confidence, symbol);
        if (!Boolean.TRUE.equals(forecast.get("available"))) {
            return forecast;
        }

        List<BigDecimal> closes = TrendForecast.positiveCloses(closesRaw);
        BigDecimal slopePct = QuoteMetrics.decimal(forecast.get("slope_pct_per_day"));

        Map<String, Object> result = new LinkedHashMap<>(forecast);
        result.put("indicators", indicators(closes));
        result.put("direction", direction(slopePct));
        return result;
    }

    /** 对应 Python 的 {@code _trend_indicators}。 */
    static Map<String, Object> indicators(List<BigDecimal> closes) {
        BigDecimal last = closes.get(closes.size() - 1);
        BigDecimal sma5 = KlineMetrics.sma(closes, 5);
        BigDecimal sma20 = KlineMetrics.sma(closes, 20);
        BigDecimal ema12 = KlineMetrics.ema(closes, 12);
        BigDecimal rsi14 = KlineMetrics.rsi(closes, 14);
        List<BigDecimal> window = closes.subList(
                Math.max(0, closes.size() - INDICATOR_WINDOW), closes.size());

        // 均线排列：两条均线都算得出才有结论，否则为 null（不是空字符串）
        String maTrend = null;
        if (sma5 != null && sma20 != null) {
            int order = sma5.compareTo(sma20);
            maTrend = order > 0 ? MA_BULLISH : order < 0 ? MA_BEARISH : MA_FLAT;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sma5", QuoteMetrics.fmt(sma5, QuoteMetrics.SCALE_PRICE));
        result.put("sma20", QuoteMetrics.fmt(sma20, QuoteMetrics.SCALE_PRICE));
        result.put("ema12", QuoteMetrics.fmt(ema12, QuoteMetrics.SCALE_PRICE));
        result.put("rsi14", QuoteMetrics.fmt(rsi14, QuoteMetrics.SCALE_PCT));
        result.put("distance_to_sma20_pct", sma20 == null
                ? null
                : QuoteMetrics.fmt(QuoteMetrics.percent(last.subtract(sma20), sma20), QuoteMetrics.SCALE_PCT));
        result.put("support20", QuoteMetrics.fmt(Collections.min(window), QuoteMetrics.SCALE_PRICE));
        result.put("resistance20", QuoteMetrics.fmt(Collections.max(window), QuoteMetrics.SCALE_PRICE));
        result.put("ma_trend", maTrend);
        return result;
    }

    /** 对应 Python 的 {@code _trend_direction}：阈值 ±0.005，恰好等于阈值算横盘。 */
    static Map<String, Object> direction(BigDecimal slopePct) {
        String key;
        String label;
        if (slopePct == null) {
            key = "flat";
            label = DIRECTION_FLAT;
        } else if (slopePct.compareTo(DIRECTION_THRESHOLD) > 0) {
            key = "up";
            label = DIRECTION_UP;
        } else if (slopePct.compareTo(DIRECTION_THRESHOLD.negate()) < 0) {
            key = "down";
            label = DIRECTION_DOWN;
        } else {
            key = "flat";
            label = DIRECTION_FLAT;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("label", label);
        return result;
    }
}