package com.jarvis.research.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘组合风险指标（Phase 2 ⑧ 统一口径）。
 *
 * <p>逐字对应 Python 的 {@code research_tools.portfolio_metrics}：同样的缺省值处理、
 * 同样的键顺序、同样的 6 位/4 位小数契约。
 *
 * <p>三处容易搞错、已按 Python 原样保留的细节：
 * <ul>
 *   <li>集中度用的是**从已格式化的 6 位小数字符串回解析**出来的 exposure，
 *       不是原始乘积——量化的先后会影响结果；</li>
 *   <li>{@code stale} 用的是 Python 真值语义：非空字符串（哪怕是 "false"）为真，
 *       所以复用 {@link QuoteMetrics#truthy}，不要用 Boolean.parseBoolean；</li>
 *   <li>数据质量标签有**优先级**：只要存在过期报价就是 STALE_QUOTES，
 *       此时账务校验失败也不再体现。</li>
 * </ul>
 */
public final class PortfolioMetrics {

    private static final MathContext DIVISION = MathContext.DECIMAL128;

    /** 账务恒等式容差。 */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** 维持担保比例阈值：低于 15 为危险，低于 25 为警戒。 */
    private static final BigDecimal MAINTENANCE_DANGER = new BigDecimal("15");
    private static final BigDecimal MAINTENANCE_WARN = new BigDecimal("25");

    private PortfolioMetrics() {
    }

    public static Map<String, Object> compute(Map<String, Object> payload) {
        if (payload == null) {
            return unavailable();
        }

        BigDecimal cash = decimalOrZero(payload.get("cash"));
        BigDecimal initialCash = decimalOrZero(payload.get("initialCash"));
        BigDecimal accountLoan = decimalOrZero(payload.get("loanBalance"));
        BigDecimal frozenMargin = decimalOrZero(payload.get("frozenMargin"));
        Map<String, Object> rawPositions = asStringKeyedMap(payload.get("positions"));

        List<Map<String, Object>> positions = new ArrayList<>();
        BigDecimal grossExposure = BigDecimal.ZERO;
        BigDecimal totalPositionLoan = BigDecimal.ZERO;
        BigDecimal totalMargin = BigDecimal.ZERO;

        for (Map.Entry<String, Object> entry : rawPositions.entrySet()) {
            Map<String, Object> raw = asStringKeyedMap(entry.getValue());
            if (raw.isEmpty() && !(entry.getValue() instanceof Map<?, ?>)) {
                continue; // 非对象条目直接跳过（Python 的 isinstance(raw, dict) 判断）
            }
            BigDecimal quantity = decimalOrZero(raw.get("quantity"));
            BigDecimal currentPrice = decimalOrZero(raw.get("currentPrice"));
            BigDecimal exposure = quantity.multiply(currentPrice);
            BigDecimal loan = decimalOrZero(raw.get("loan"));
            BigDecimal margin = decimalOrZero(raw.get("marginUsed"));
            BigDecimal invested = loan.add(margin);
            BigDecimal pnl = exposure.subtract(invested);
            // margin <= 0 时不计算 ROE（键仍是 null），与 Python 的 if margin > 0 一致
            BigDecimal roe = margin.signum() > 0 ? QuoteMetrics.percent(pnl, margin) : null;

            grossExposure = grossExposure.add(exposure);
            totalPositionLoan = totalPositionLoan.add(loan);
            totalMargin = totalMargin.add(margin);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("symbol", entry.getKey());
            item.put("quantity", QuoteMetrics.fmt(quantity, QuoteMetrics.SCALE_PRICE));
            item.put("current_price", QuoteMetrics.fmt(currentPrice, QuoteMetrics.SCALE_PRICE));
            item.put("exposure", QuoteMetrics.fmt(exposure, QuoteMetrics.SCALE_PRICE));
            item.put("loan", QuoteMetrics.fmt(loan, QuoteMetrics.SCALE_PRICE));
            item.put("margin_used", QuoteMetrics.fmt(margin, QuoteMetrics.SCALE_PRICE));
            item.put("unrealized_pnl", QuoteMetrics.fmt(pnl, QuoteMetrics.SCALE_PRICE));
            item.put("return_on_equity_pct", QuoteMetrics.fmt(roe, QuoteMetrics.SCALE_PCT));
            item.put("quote_time", raw.get("quoteTime"));
            item.put("stale", QuoteMetrics.truthy(raw.get("stale")));
            positions.add(item);
        }

        BigDecimal netEquity = cash.add(grossExposure).subtract(accountLoan);
        BigDecimal totalAssets = cash.add(grossExposure);
        BigDecimal maintenance = grossExposure.signum() > 0
                ? QuoteMetrics.percent(netEquity, grossExposure)
                : new BigDecimal("100");
        BigDecimal grossLeverage = netEquity.signum() > 0
                ? grossExposure.divide(netEquity, DIVISION)
                : null;
        BigDecimal totalReturn = initialCash.signum() > 0
                ? QuoteMetrics.percent(netEquity.subtract(initialCash), initialCash)
                : null;

        String riskStatus = "NONE";
        if (grossExposure.signum() > 0 && maintenance != null) {
            riskStatus = maintenance.compareTo(MAINTENANCE_DANGER) < 0 ? "DANGER"
                    : maintenance.compareTo(MAINTENANCE_WARN) < 0 ? "WARN" : "SAFE";
        }

        BigDecimal maxConcentration = BigDecimal.ZERO;
        int stalePositionCount = 0;
        for (Map<String, Object> item : positions) {
            // 注意：从格式化后的字符串回解析，而不是用原始乘积重算
            BigDecimal exposure = decimalOrZero(item.get("exposure"));
            BigDecimal concentration = grossExposure.signum() > 0
                    ? QuoteMetrics.percent(exposure, grossExposure)
                    : null;
            item.put("concentration_pct", QuoteMetrics.fmt(concentration, QuoteMetrics.SCALE_PCT));
            if (concentration != null && concentration.compareTo(maxConcentration) > 0) {
                maxConcentration = concentration;
            }
            if (Boolean.TRUE.equals(item.get("stale"))) {
                stalePositionCount++;
            }
        }

        boolean accountingOk = accountLoan.subtract(totalPositionLoan).abs().compareTo(TOLERANCE) <= 0
                && frozenMargin.subtract(totalMargin).abs().compareTo(TOLERANCE) <= 0;
        // 过期报价优先：账务不平在这一档不再单独体现
        String dataQualityStatus = stalePositionCount > 0
                ? "STALE_QUOTES"
                : (accountingOk ? "OK" : "ACCOUNTING_MISMATCH");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("account_status", payload.get("status"));
        result.put("cash", QuoteMetrics.fmt(cash, QuoteMetrics.SCALE_PRICE));
        result.put("initial_cash", QuoteMetrics.fmt(initialCash, QuoteMetrics.SCALE_PRICE));
        result.put("gross_exposure", QuoteMetrics.fmt(grossExposure, QuoteMetrics.SCALE_PRICE));
        result.put("total_assets", QuoteMetrics.fmt(totalAssets, QuoteMetrics.SCALE_PRICE));
        result.put("loan_balance", QuoteMetrics.fmt(accountLoan, QuoteMetrics.SCALE_PRICE));
        result.put("position_loan_sum", QuoteMetrics.fmt(totalPositionLoan, QuoteMetrics.SCALE_PRICE));
        result.put("frozen_margin", QuoteMetrics.fmt(frozenMargin, QuoteMetrics.SCALE_PRICE));
        result.put("position_margin_sum", QuoteMetrics.fmt(totalMargin, QuoteMetrics.SCALE_PRICE));
        result.put("net_equity", QuoteMetrics.fmt(netEquity, QuoteMetrics.SCALE_PRICE));
        result.put("gross_leverage", QuoteMetrics.fmt(grossLeverage, QuoteMetrics.SCALE_PCT));
        result.put("maintenance_margin_pct", QuoteMetrics.fmt(maintenance, QuoteMetrics.SCALE_PCT));
        result.put("total_return_pct", QuoteMetrics.fmt(totalReturn, QuoteMetrics.SCALE_PCT));
        result.put("risk_status", riskStatus);
        result.put("max_position_concentration_pct",
                positions.isEmpty() ? null : QuoteMetrics.fmt(maxConcentration, QuoteMetrics.SCALE_PCT));
        result.put("stale_position_count", stalePositionCount);
        result.put("accounting_invariant_ok", accountingOk);
        result.put("data_quality_status", dataQualityStatus);
        result.put("positions", positions);
        return result;
    }

    /** 与 Python 的 {@code _decimal(x) or Decimal("0")} 等价：缺省与非数值一律按 0 处理。 */
    static BigDecimal decimalOrZero(Object value) {
        BigDecimal parsed = QuoteMetrics.decimal(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Map<String, Object> asStringKeyedMap(Object value) {
        return value instanceof Map<?, ?> raw ? stringKeyed(raw) : new LinkedHashMap<>();
    }

    private static Map<String, Object> unavailable() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("reason", "no_portfolio_data");
        return result;
    }
}