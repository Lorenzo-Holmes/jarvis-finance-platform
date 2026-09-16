package com.jarvis.research.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 趋势区间预测（Phase 2 ⑧ 统一口径）。
 *
 * <p>逐字对应 Python 的 {@code research_tools.trend_forecast}：同样的样本门槛、
 * 同样的裁剪规则、同样的键顺序与格式化契约。
 *
 * <p><b>关于 z 分位数</b>：Python 先查一张可审计常量表，只有表外的置信度才调用
 * {@code statistics.NormalDist().inv_cdf}。本类同样先查表；表外值则用
 * <b>高精度 erf 级数 + 二分</b>直接求标准正态分位数——**刻意不默写 CPython 的
 * AS241 有理逼近系数**：三十多个常数凭记忆抄写，抄错一位不会有任何报错，
 * 而结果会长期静默偏移。两条路径的差异约 1e-16 相对量级，远小于输出量化精度
 * （6 位/4 位小数），但这一点靠实测确认，不靠推理——契约测试里专门用表外的
 * 置信度 0.98 比对两端。
 */
public final class TrendForecast {

    /** 最少样本根数（不足则判定不可用）。 */
    static final int MIN_BARS = 20;

    /** 收益率最少个数（波动率估计需要）。 */
    static final int MIN_RETURNS = 20;

    /** 默认预测天数（交易日）。 */
    static final int DEFAULT_HORIZON = 5;

    /** 预测天数上限。 */
    static final int MAX_HORIZON = 60;

    /** 斜率拟合使用的回看窗口。 */
    static final int LOOKBACK = 20;

    private static final MathContext DIVISION = MathContext.DECIMAL128;
    private static final MathContext HIGH_PRECISION = new MathContext(40);

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /** 可审计的常见置信度常量（与 Python 的 table 完全一致）。 */
    private static final String[][] CONFIDENCE_TABLE = {
            {"0.99", "2.5758"},
            {"0.975", "2.2414"},
            {"0.95", "1.9600"},
            {"0.90", "1.6449"},
            {"0.80", "1.2816"},
            {"0.50", "0.6745"},
    };

    private TrendForecast() {
    }

    /**
     * @param closesRaw   历史收盘价（Java 侧由自营行情库注入）
     * @param horizonDays 预测天数，缺省 5，裁剪到 [1, 60]
     * @param confidence  置信度，缺省 0.95，裁剪到 [0.5, 0.99]
     * @param symbol      标的（原样转字符串）
     */
    public static Map<String, Object> compute(Object closesRaw, Object horizonDays,
                                              Object confidence, Object symbol) {
        List<BigDecimal> closes = positiveCloses(closesRaw);
        int bars = closes.size();
        if (bars < MIN_BARS) {
            return unavailable(bars);
        }

        BigDecimal horizonValue = QuoteMetrics.decimal(horizonDays);
        if (horizonValue == null) {
            horizonValue = BigDecimal.valueOf(DEFAULT_HORIZON);
        }
        int horizon = clamp(horizonValue, BigDecimal.ONE, BigDecimal.valueOf(MAX_HORIZON)).intValue();

        BigDecimal conf = QuoteMetrics.decimal(confidence);
        if (conf == null) {
            conf = new BigDecimal("0.95");
        }
        // 注意是 [0.5, 0.99]：下界用 compareTo 判断，别用 signum
        conf = conf.min(new BigDecimal("0.99")).max(new BigDecimal("0.5"));

        List<BigDecimal> returns = new ArrayList<>();
        for (int index = 1; index < closes.size(); index++) {
            returns.add(closes.get(index)
                    .divide(closes.get(index - 1), DIVISION)
                    .subtract(BigDecimal.ONE));
        }
        if (returns.size() < MIN_RETURNS) {
            return unavailable(bars);
        }

        BigDecimal lastClose = closes.get(closes.size() - 1);
        List<BigDecimal> window = closes.subList(Math.max(0, bars - LOOKBACK), bars);
        BigDecimal slope = linearSlope(window);
        if (slope == null) {
            slope = BigDecimal.ZERO;
        }

        BigDecimal center = lastClose.add(slope.multiply(BigDecimal.valueOf(horizon)));

        BigDecimal std = stdDev(returns);
        if (std == null) {
            return unavailable(bars);
        }
        BigDecimal z = zScoreFor(conf);
        // band = z * std * sqrt(horizon) * last_close
        BigDecimal band = z.multiply(std)
                .multiply(BigDecimal.valueOf(horizon).sqrt(DIVISION))
                .multiply(lastClose);
        BigDecimal lower = center.subtract(band);
        BigDecimal upper = center.add(band);
        if (lower.signum() <= 0) {
            lower = lastClose.multiply(new BigDecimal("0.01"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("symbol", symbol == null ? null : String.valueOf(symbol));
        result.put("horizon_days", horizon);
        result.put("confidence", QuoteMetrics.fmt(conf, QuoteMetrics.SCALE_PRICE));
        result.put("bars", bars);
        result.put("last_close", QuoteMetrics.fmt(lastClose, QuoteMetrics.SCALE_PRICE));
        result.put("center", QuoteMetrics.fmt(center, QuoteMetrics.SCALE_PRICE));
        result.put("lower", QuoteMetrics.fmt(lower, QuoteMetrics.SCALE_PRICE));
        result.put("upper", QuoteMetrics.fmt(upper, QuoteMetrics.SCALE_PRICE));
        result.put("change_to_center_pct",
                QuoteMetrics.fmt(QuoteMetrics.percent(center.subtract(lastClose), lastClose), QuoteMetrics.SCALE_PCT));
        result.put("slope_pct_per_day",
                QuoteMetrics.fmt(QuoteMetrics.percent(slope, lastClose), QuoteMetrics.SCALE_PCT));
        result.put("band_pct",
                QuoteMetrics.fmt(QuoteMetrics.percent(band, lastClose), QuoteMetrics.SCALE_PCT));
        result.put("vol_daily_pct",
                QuoteMetrics.fmt(std.multiply(ONE_HUNDRED), QuoteMetrics.SCALE_PCT));
        return result;
    }

    /** 对应 Python 的 {@code _linear_slope}：x 取 0..n-1 的最小二乘斜率。 */
    static BigDecimal linearSlope(List<BigDecimal> values) {
        int count = values.size();
        if (count < 2) {
            return null;
        }
        BigDecimal meanX = BigDecimal.valueOf(count - 1L)
                .divide(BigDecimal.valueOf(2), DIVISION); // mean of 0..n-1
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        BigDecimal meanY = sum.divide(BigDecimal.valueOf(count), DIVISION);

        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (int index = 0; index < count; index++) {
            BigDecimal dx = BigDecimal.valueOf(index).subtract(meanX);
            numerator = numerator.add(dx.multiply(values.get(index).subtract(meanY)));
            denominator = denominator.add(dx.multiply(dx));
        }
        if (denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, DIVISION);
    }

    /**
     * 对应 Python 的 {@code _std_dev}：**样本**标准差（分母 n-1）。
     * 方差为 0（恒定序列）时 Python 返回 0 而不是 None，必须一致。
     */
    static BigDecimal stdDev(List<BigDecimal> values) {
        int count = values.size();
        if (count < 2) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(count), DIVISION);
        BigDecimal squares = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            BigDecimal deviation = value.subtract(mean);
            squares = squares.add(deviation.multiply(deviation));
        }
        BigDecimal variance = squares.divide(BigDecimal.valueOf(count - 1L), DIVISION);
        if (variance.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return variance.sqrt(DIVISION);
    }

    /** 对应 Python 的 {@code _z_score_for}：先查表，表外再求分位数。 */
    static BigDecimal zScoreFor(BigDecimal confidence) {
        for (String[] entry : CONFIDENCE_TABLE) {
            if (confidence.compareTo(new BigDecimal(entry[0])) == 0) {
                return new BigDecimal(entry[1]);
            }
        }
        // Python 是 Decimal(str(NormalDist().inv_cdf(float((1 + confidence) / 2))))
        BigDecimal probability = BigDecimal.ONE.add(confidence)
                .divide(BigDecimal.valueOf(2), DIVISION);
        double quantile = quantile(probability).doubleValue();
        return new BigDecimal(Double.toString(quantile));
    }

    /**
     * 标准正态分位数：对 Φ 做二分。
     *
     * <p>不使用任何有理逼近常数：Φ 由 erf 的幂级数求值，二分 200 次收敛到
     * {@code HIGH_PRECISION} 的位数上限。**但要如实说明精度边界**：级数里的 sqrt(pi)
     * 取自 {@code Math.PI}（double，约 1e-16 相对误差），所以实际精度约 1e-16 量级，
     * 与 CPython 的 AS241 逼近同量级——不是"40 位精确"。对本函数而言足够：
     * 差异远小于输出量化精度（6 位/4 位小数）。
     *
     * <p>置信度被夹在 [0.5, 0.99]，故 p ∈ [0.75, 0.995]、z ∈ [0.674, 2.576]，
     * 级数在此区间收敛良好。
     */
    static BigDecimal quantile(BigDecimal probability) {
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = new BigDecimal("4");
        for (int step = 0; step < 200; step++) {
            BigDecimal middle = low.add(high).divide(BigDecimal.valueOf(2), HIGH_PRECISION);
            if (normalCdf(middle).compareTo(probability) < 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low.add(high).divide(BigDecimal.valueOf(2), HIGH_PRECISION);
    }

    /** Φ(x) = 0.5 * (1 + erf(x / sqrt(2))) */
    static BigDecimal normalCdf(BigDecimal x) {
        BigDecimal root2 = BigDecimal.valueOf(2).sqrt(HIGH_PRECISION);
        BigDecimal erf = erf(x.divide(root2, HIGH_PRECISION));
        return BigDecimal.valueOf(0.5).multiply(BigDecimal.ONE.add(erf));
    }

    /**
     * erf(z) = 2/sqrt(pi) * Σ (-1)^n z^(2n+1) / (n! (2n+1))
     *
     * <p>在 z ≤ 2.6 的范围内各项单调衰减（n 超过 z² 之后），不存在严重相消。
     */
    static BigDecimal erf(BigDecimal z) {
        BigDecimal rootPi = BigDecimal.valueOf(Math.PI).sqrt(HIGH_PRECISION);
        BigDecimal twoOverRootPi = BigDecimal.valueOf(2).divide(rootPi, HIGH_PRECISION);

        BigDecimal term = z;                 // n = 0 项里的 z^(2n+1)
        BigDecimal factorial = BigDecimal.ONE;
        BigDecimal zSquared = z.multiply(z);
        BigDecimal total = BigDecimal.ZERO;
        for (int n = 0; n < 60; n++) {
            if (n > 0) {
                factorial = factorial.multiply(BigDecimal.valueOf(n));
                term = term.multiply(zSquared);
            }
            BigDecimal contribution = term.divide(
                    factorial.multiply(BigDecimal.valueOf(2L * n + 1)), HIGH_PRECISION);
            total = (n % 2 == 0) ? total.add(contribution) : total.subtract(contribution);
        }
        return twoOverRootPi.multiply(total);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal low, BigDecimal high) {
        return value.max(low).min(high);
    }

    /**
     * 只保留可解析且为正的收盘价（0 与负价一律丢弃，与 risk_metrics 口径一致）。
     *
     * <p>market_trend 需要拿**原始入参**重新过滤一遍，所以抽成包内可复用的方法：
     * 两处各写一份过滤条件，迟早会因为只改了一处而悄悄分歧。
     */
    static List<BigDecimal> positiveCloses(Object closesRaw) {
        List<BigDecimal> closes = new ArrayList<>();
        for (Object raw : asIterable(closesRaw)) {
            BigDecimal value = QuoteMetrics.decimal(raw);
            if (value != null && value.signum() > 0) {
                closes.add(value);
            }
        }
        return closes;
    }

    private static Iterable<?> asIterable(Object raw) {
        if (raw instanceof Collection<?> items) {
            return items;
        }
        // Java 侧总是传 JSON 数组（List）；其它类型按"没有样本"处理
        return List.of();
    }

    private static Map<String, Object> unavailable(int bars) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("reason", "insufficient_closes");
        result.put("bars", bars);
        return result;
    }

    /** 供测试与调用方复用的舍入契约说明用常量。 */
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
}