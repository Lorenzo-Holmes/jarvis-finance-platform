package com.jarvis.research.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险指标（历史模拟法）：VaR / ES / 年化波动率 / 最大回撤 + 阈值告警。
 *
 * <p>这是 Phase 2 ⑧「统一两套确定性计算口径」迁移到 Java 的第一步。之所以从风险指标开始：
 * 它是一整套独立的口径（分位数、尾部均值、样本标准差、回撤、阈值规则），
 * 迁移完就有了可复制的模板，剩下几处照做。</p>
 *
 * <h2>迁移的第一原则：先保持输出完全一致</h2>
 * <p>原实现（backend/app/research_tools.py 的 risk_metrics）返回的是**格式化后的字符串**
 * 而不是数字——{@code "var_pct": "-2.1400"}、{@code "confidence": "0.950000"}。
 * 前端与提示词都按这个形状在消费，所以本类用 {@link BigDecimal} 复现同样的量化与四舍五入，
 * 输出逐字相同的字符串。**迁移不该顺手改变口径**，否则"统一"会变成"两个口径都变了"，
 * 而且没人能说清差异来自重构还是来自精度。</p>
 *
 * <h2>一处刻意保留的差异（需要一次单独的决定）</h2>
 * <p>输入过滤沿用了原实现的 {@code > 0}，也就是**负数价格会被丢掉**。
 * 而 {@link MarketMetrics} 用的是 {@code != 0}（保留负价，因为 2020 年 4 月 WTI 原油
 * 期货真的收在负值）。两者不一致，但这里**不改**：改了就改变了已产出数字的口径，
 * 那该是一次独立决定，不该藏在迁移里。已为这条差异单独写了测试钉住现状。</p>
 */
public final class RiskMetrics {

    /** 最少样本根数（不足则判定不可用，而不是给一个样本太少的"结论"）。 */
    static final int MIN_BARS = 10;
    /** 收益率最少个数。 */
    static final int MIN_RETURNS = 10;
    static final BigDecimal TRADING_DAYS_PER_YEAR = new BigDecimal("252");

    static final BigDecimal VAR_HIGH_PCT = new BigDecimal("4");
    static final BigDecimal VAR_MEDIUM_PCT = new BigDecimal("2");
    static final BigDecimal ES_HIGH_PCT = new BigDecimal("5");
    static final BigDecimal MDD_MEDIUM_PCT = new BigDecimal("20");

    /** 与原实现一致的精度：_fmt 默认 6 位小数。 */
    static final int SCALE_DEFAULT = 6;
    /** 百分比类字段 4 位小数。 */
    static final int SCALE_PCT = 4;

    private static final MathContext CONTEXT = MathContext.DECIMAL128;

    private RiskMetrics() {
    }

    public record Alert(String level, String metric, String rule, String message) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("level", level);
            map.put("metric", metric);
            map.put("rule", rule);
            map.put("message", message);
            return map;
        }
    }

    /**
     * 计算结果。{@code available=false} 时只有 reason 与 bars 有意义。
     *
     * @param closes        历史收盘价（升序）
     * @param confidence    置信度，null 时取 0.95，越界夹到 [0.5, 0.99]
     * @param portfolioValue 可选的账户资金，用于换算单日潜在亏损金额
     */
    public record Result(String reason, String symbol, String confidence, int bars, String lastClose,
                         String varPct, String esPct, String volAnnualPct, String maxDrawdownPct,
                         String varAmount, List<Alert> alerts) {

        public boolean available() {
            return reason == null;
        }

        /** 字段顺序与原实现一致，便于逐字比对与落库。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (!available()) {
                map.put("available", false);
                map.put("reason", reason);
                map.put("bars", bars);
                return map;
            }
            map.put("available", true);
            map.put("symbol", symbol);
            map.put("confidence", confidence);
            map.put("bars", bars);
            map.put("last_close", lastClose);
            map.put("var_pct", varPct);
            map.put("es_pct", esPct);
            map.put("vol_annual_pct", volAnnualPct);
            map.put("max_drawdown_pct", maxDrawdownPct);
            List<Map<String, Object>> alertMaps = new ArrayList<>();
            for (Alert alert : alerts) {
                alertMaps.add(alert.toMap());
            }
            map.put("alerts", alertMaps);
            if (varAmount != null) {
                map.put("var_amount", varAmount);
            }
            return map;
        }
    }

    public static Result compute(List<?> closesRaw, Object confidence, Object portfolioValue, Object symbol) {
        List<BigDecimal> closes = new ArrayList<>();
        for (Object raw : closesRaw == null ? List.of() : closesRaw) {
            BigDecimal value = decimal(raw);
            // 与原实现一致的过滤：> 0。注意这会丢掉负价（见类注释里的刻意保留差异）
            if (value != null && value.signum() > 0) {
                closes.add(value);
            }
        }
        int bars = closes.size();
        if (bars < MIN_BARS) {
            return unavailable("insufficient_closes", bars);
        }

        BigDecimal conf = decimal(confidence);
        if (conf == null) {
            conf = new BigDecimal("0.95");
        }
        conf = conf.max(new BigDecimal("0.5")).min(new BigDecimal("0.99"));

        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            returns.add(closes.get(i).divide(closes.get(i - 1), CONTEXT).subtract(BigDecimal.ONE));
        }
        if (returns.size() < MIN_RETURNS) {
            return unavailable("insufficient_closes", bars);
        }

        BigDecimal alpha = BigDecimal.ONE.subtract(conf);
        List<BigDecimal> sorted = new ArrayList<>(returns);
        sorted.sort(BigDecimal::compareTo);
        int n = sorted.size();
        // 与原实现一致：k = max(1, round_half_up(n * alpha))
        int k = Math.max(1, new BigDecimal(n).multiply(alpha)
                .setScale(0, RoundingMode.HALF_UP).intValueExact());
        BigDecimal varLoss = sorted.get(k - 1);
        BigDecimal esLoss = BigDecimal.ZERO;
        for (int i = 0; i < k; i++) {
            esLoss = esLoss.add(sorted.get(i));
        }
        esLoss = esLoss.divide(new BigDecimal(k), CONTEXT);

        BigDecimal std = sampleStdDev(returns);
        BigDecimal volAnnual = std == null ? null
                : std.multiply(TRADING_DAYS_PER_YEAR.sqrt(CONTEXT), CONTEXT);

        BigDecimal peak = closes.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (int i = 1; i < closes.size(); i++) {
            BigDecimal price = closes.get(i);
            if (price.compareTo(peak) > 0) {
                peak = price;
            }
            BigDecimal drawdown = price.divide(peak, CONTEXT).subtract(BigDecimal.ONE);
            if (drawdown.compareTo(maxDrawdown) < 0) {
                maxDrawdown = drawdown;
            }
        }

        BigDecimal varAbs = varLoss.multiply(new BigDecimal("100")).abs();
        BigDecimal esAbs = esLoss.multiply(new BigDecimal("100")).abs();
        BigDecimal mddAbs = maxDrawdown.multiply(new BigDecimal("100")).abs();

        List<Alert> alerts = new ArrayList<>();
        if (varAbs.compareTo(VAR_HIGH_PCT) >= 0) {
            alerts.add(new Alert("high", "var", "单日VaR绝对值 >= " + plain(VAR_HIGH_PCT) + "%",
                    "单日最大预期亏损约 " + format(varAbs, SCALE_PCT) + "%，风险敞口偏高。"));
        } else if (varAbs.compareTo(VAR_MEDIUM_PCT) >= 0) {
            alerts.add(new Alert("medium", "var", "单日VaR绝对值 >= " + plain(VAR_MEDIUM_PCT) + "%",
                    "单日最大预期亏损约 " + format(varAbs, SCALE_PCT) + "%，需留意波动放大。"));
        }
        if (esAbs.compareTo(ES_HIGH_PCT) >= 0) {
            alerts.add(new Alert("high", "es", "尾部风险ES绝对值 >= " + plain(ES_HIGH_PCT) + "%",
                    "极端情形平均亏损约 " + format(esAbs, SCALE_PCT) + "%，尾部风险显著。"));
        }
        if (mddAbs.compareTo(MDD_MEDIUM_PCT) >= 0) {
            alerts.add(new Alert("medium", "max_drawdown", "历史最大回撤 >= " + plain(MDD_MEDIUM_PCT) + "%",
                    "样本区间最大回撤约 " + format(mddAbs, SCALE_PCT) + "%，注意仓位控制。"));
        }

        BigDecimal portfolio = decimal(portfolioValue);
        boolean hasPortfolio = portfolio != null && portfolio.signum() > 0;
        BigDecimal varAmount = null;
        if (hasPortfolio) {
            varAmount = varLoss.multiply(portfolio, CONTEXT);
            if (varAbs.compareTo(VAR_MEDIUM_PCT) >= 0) {
                alerts.add(new Alert("medium", "portfolio_var", "账户单日潜在亏损占比较高",
                        "按资金规模估算，单日潜在亏损约 " + format(varAmount.abs(), SCALE_DEFAULT)
                                + " 元，建议评估仓位。"));
            }
        }

        if (alerts.isEmpty()) {
            if (hasPortfolio) {
                alerts.add(new Alert("low", "overall", "无阈值命中",
                        "当前样本未命中高风险阈值；按资金规模估算单日潜在亏损约 "
                                + format(varLoss.multiply(portfolio, CONTEXT).abs(), SCALE_DEFAULT) + " 元。"));
            } else {
                alerts.add(new Alert("low", "overall", "无阈值命中",
                        "当前样本未命中高风险阈值，维持常规监控。"));
            }
        }

        return new Result(null,
                symbol == null ? null : String.valueOf(symbol),
                format(conf, SCALE_DEFAULT),
                bars,
                format(closes.get(closes.size() - 1), SCALE_DEFAULT),
                format(varLoss.multiply(new BigDecimal("100")), SCALE_PCT),
                format(esLoss.multiply(new BigDecimal("100")), SCALE_PCT),
                volAnnual == null ? null : format(volAnnual.multiply(new BigDecimal("100")), SCALE_PCT),
                format(maxDrawdown.multiply(new BigDecimal("100")), SCALE_PCT),
                varAmount == null ? null : format(varAmount, SCALE_DEFAULT),
                alerts);
    }

    private static Result unavailable(String reason, int bars) {
        return new Result(reason, null, null, bars, null, null, null, null, null, null, List.of());
    }

    /** 样本标准差（分母 n-1）；不足两个样本返回 null。 */
    private static BigDecimal sampleStdDev(List<BigDecimal> values) {
        int n = values.size();
        if (n < 2) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        BigDecimal mean = sum.divide(new BigDecimal(n), CONTEXT);
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            BigDecimal diff = value.subtract(mean);
            variance = variance.add(diff.multiply(diff, CONTEXT), CONTEXT);
        }
        variance = variance.divide(new BigDecimal(n - 1), CONTEXT);
        if (variance.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return variance.sqrt(CONTEXT);
    }

    /** 等价于原实现的 {@code Decimal(str(value))}：非有限值或解析失败返回 null。 */
    static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(value).trim());
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 等价于原实现的 {@code format(value.quantize(quantum, ROUND_HALF_UP), "f")}。 */
    static String format(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}