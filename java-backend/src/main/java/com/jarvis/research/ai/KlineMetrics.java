package com.jarvis.research.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K 线技术指标（Phase 2 ⑧ 统一口径）。
 *
 * <p>逐字对应 Python 的 {@code research_tools.kline_metrics}：同样的可用性判断、
 * 同样的键顺序、同样的「指标 6 位小数 / RSI 4 位小数」契约。
 *
 * <p>格式化与解析复用 {@link QuoteMetrics#decimal}、{@link QuoteMetrics#fmt}、
 * {@link QuoteMetrics#percent}——同包、同一套语义。**不在本类里再抄一份**：
 * 两套 decimal/fmt 迟早会在边界（如不可解析值、除数为 0）上跑偏。
 */
public final class KlineMetrics {

    private static final MathContext DIVISION = MathContext.DECIMAL128;

    /** 支撑/压力位只看最近 20 根。 */
    private static final int RECENT_BARS = 20;

    private KlineMetrics() {
    }

    public static Map<String, Object> compute(Map<String, Object> payload) {
        List<Map<String, Object>> rows = validRows(payload);
        if (rows.isEmpty()) {
            return unavailable("no_kline_data");
        }

        List<BigDecimal> closes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal close = QuoteMetrics.decimal(row.get("close"));
            if (close != null) {
                closes.add(close);
            }
        }
        if (closes.isEmpty()) {
            return unavailable("no_close_data");
        }

        BigDecimal last = closes.get(closes.size() - 1);
        BigDecimal sma5 = sma(closes, 5);
        BigDecimal sma20 = sma(closes, 20);
        BigDecimal ema12 = ema(closes, 12);
        BigDecimal rsi14 = rsi(closes, 14);

        List<Map<String, Object>> recent = rows.subList(Math.max(0, rows.size() - RECENT_BARS), rows.size());
        List<BigDecimal> lows = valuesOf(recent, "low");
        List<BigDecimal> highs = valuesOf(recent, "high");
        // Python 是 `min(lows) if lows else None`：没有可用的 low 时是 None，不是 0
        BigDecimal support20 = lows.isEmpty() ? null : Collections.min(lows);
        BigDecimal resistance20 = highs.isEmpty() ? null : Collections.max(highs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("bars", rows.size());
        // date 原样透传（不解析、不格式化）：可能是字符串，也可能缺失
        result.put("start", rows.get(0).get("date"));
        result.put("end", rows.get(rows.size() - 1).get("date"));
        result.put("last_close", QuoteMetrics.fmt(last, QuoteMetrics.SCALE_PRICE));
        result.put("sma5", QuoteMetrics.fmt(sma5, QuoteMetrics.SCALE_PRICE));
        result.put("sma20", QuoteMetrics.fmt(sma20, QuoteMetrics.SCALE_PRICE));
        result.put("ema12", QuoteMetrics.fmt(ema12, QuoteMetrics.SCALE_PRICE));
        result.put("rsi14", QuoteMetrics.fmt(rsi14, QuoteMetrics.SCALE_PCT));
        // 只有 sma20 存在时才计算偏离度；分母为 0 时 _percent 返回 null（键仍在）
        result.put("distance_to_sma20_pct", sma20 == null
                ? null
                : QuoteMetrics.fmt(QuoteMetrics.percent(last.subtract(sma20), sma20), QuoteMetrics.SCALE_PCT));
        result.put("support20", QuoteMetrics.fmt(support20, QuoteMetrics.SCALE_PRICE));
        result.put("resistance20", QuoteMetrics.fmt(resistance20, QuoteMetrics.SCALE_PRICE));
        return result;
    }

    /** 对应 Python 的 {@code _valid_rows}：data 必须是列表，元素必须是对象且 close 可解析。 */
    static List<Map<String, Object>> validRows(Map<String, Object> payload) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (payload == null || !(payload.get("data") instanceof List<?> items)) {
            return rows;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            raw.forEach((key, value) -> row.put(String.valueOf(key), value));
            if (QuoteMetrics.decimal(row.get("close")) == null) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    /** 对应 Python 的 {@code _sma}：不足 period 根返回 null，否则取**最后** period 根的均值。 */
    static BigDecimal sma(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            return null;
        }
        return mean(values.subList(values.size() - period, values.size()));
    }

    /**
     * 对应 Python 的 {@code _ema}：以**前 period 根的均值**为种子，再逐根递推。
     * 种子不是第一根，这里弄错整条曲线都会偏。
     */
    static BigDecimal ema(List<BigDecimal> values, int period) {
        if (values.size() < period) {
            return null;
        }
        BigDecimal alpha = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1L), DIVISION);
        BigDecimal value = mean(values.subList(0, period));
        for (int index = period; index < values.size(); index++) {
            value = values.get(index).multiply(alpha)
                    .add(value.multiply(BigDecimal.ONE.subtract(alpha)));
        }
        return value;
    }

    /**
     * 对应 Python 的 {@code _rsi}：只看最近 period+1 根，涨跌幅各自平均。
     *
     * <p>平均跌幅为 0 时不能除：Python 返回 100（有涨幅）或 50（完全没有波动）。
     */
    static BigDecimal rsi(List<BigDecimal> values, int period) {
        if (values.size() < period + 1) {
            return null;
        }
        List<BigDecimal> window = values.subList(values.size() - (period + 1), values.size());
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int index = 0; index + 1 < window.size(); index++) {
            BigDecimal delta = window.get(index + 1).subtract(window.get(index));
            if (delta.signum() >= 0) {
                gains = gains.add(delta);
            } else {
                losses = losses.subtract(delta);
            }
        }
        BigDecimal averageGain = gains.divide(BigDecimal.valueOf(period), DIVISION);
        BigDecimal averageLoss = losses.divide(BigDecimal.valueOf(period), DIVISION);
        if (averageLoss.signum() == 0) {
            return averageGain.signum() > 0 ? new BigDecimal("100") : new BigDecimal("50");
        }
        BigDecimal rs = averageGain.divide(averageLoss, DIVISION);
        return new BigDecimal("100")
                .subtract(new BigDecimal("100").divide(BigDecimal.ONE.add(rs), DIVISION));
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), DIVISION);
    }

    /** 取出某一列并丢掉不可解析的值（Python 侧同样是先收集再过滤 None）。 */
    private static List<BigDecimal> valuesOf(List<Map<String, Object>> rows, String key) {
        List<BigDecimal> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal value = QuoteMetrics.decimal(row.get(key));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static Map<String, Object> unavailable(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("reason", reason);
        return result;
    }
}