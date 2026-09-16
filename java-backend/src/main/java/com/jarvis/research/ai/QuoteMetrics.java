package com.jarvis.research.ai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行情快照派生指标（Phase 2 ⑧ 统一口径）。
 *
 * <p>逐字对应 Python 的 {@code research_tools.quote_metrics}：同样的键顺序、同样的
 * 「金额 6 位小数 / 百分比 4 位小数 + ROUND_HALF_UP + toPlainString」契约、
 * 同样的「键存在但值为 null」语义（不是省略键）。跨语言一致性由两侧各自钉同一组字面量保证。
 *
 * <p>两处易被照抄错的地方，都在下面就地标明：Python 的 {@code or} 真值判断，
 * 以及除数为 0 时返回 null 而非报错。
 *
 * <p>已知微差异（不隐藏）：JSON 里传入 {@code -0.0} 时，Python 的 Decimal 保留负号
 * （format 出 {@code -0.000000}），而 Java 的 BigDecimal 在 setScale 后丢符号
 * （输出 {@code 0.000000}）。正常的行情数据不会出现 -0.0，故先记录、不猜测处理方式。
 */
public final class QuoteMetrics {

    /** 金额与价差的量子（对应 Python 的 Q6）。 */
    static final int SCALE_PRICE = 6;

    /** 百分比的量子（对应 Python 的 Q4）。 */
    static final int SCALE_PCT = 4;

    private static final MathContext DIVISION = MathContext.DECIMAL128;

    private QuoteMetrics() {
    }

    /**
     * 计算派生指标。返回的 Map 保持 Python 字典的插入顺序，便于两端逐字比对。
     */
    public static Map<String, Object> compute(Map<String, Object> priceData) {
        Map<String, Object> snapshot = priceData == null ? Map.of() : priceData;

        BigDecimal price = decimal(snapshot.get("price"));
        // Python 写的是 `prev_close or yesterday_price`：**数值 0 与空串都算假值**，会回退到
        // yesterday_price。若只判断 null，prev_close=0 的行情会得到与 Python 不同的结果。
        BigDecimal prev = decimal(truthy(snapshot.get("prev_close"))
                ? snapshot.get("prev_close")
                : snapshot.get("yesterday_price"));
        BigDecimal open = decimal(snapshot.get("open"));
        BigDecimal high = decimal(snapshot.get("high"));
        BigDecimal low = decimal(snapshot.get("low"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", fmt(price, SCALE_PRICE));
        result.put("prev_close", fmt(prev, SCALE_PRICE));
        result.put("open", fmt(open, SCALE_PRICE));
        result.put("high", fmt(high, SCALE_PRICE));
        result.put("low", fmt(low, SCALE_PRICE));
        // 同样走 `or` 语义：空串 quote_time 会回退到 time；取到的值原样透传、不做格式化。
        result.put("quote_time", truthy(snapshot.get("quote_time"))
                ? snapshot.get("quote_time")
                : snapshot.get("time"));
        result.put("source", snapshot.get("source"));

        if (price != null && prev != null) {
            result.put("change", fmt(price.subtract(prev), SCALE_PRICE));
            // prev 可能是 0 之外的值，但 _percent 仍可能因分母为 0 返回 null → 键在、值为 null
            result.put("change_pct", fmt(percent(price.subtract(prev), prev), SCALE_PCT));
        }
        if (price != null && open != null) {
            result.put("vs_open_pct", fmt(percent(price.subtract(open), open), SCALE_PCT));
        }
        if (high != null && low != null && prev != null) {
            result.put("intraday_range_pct", fmt(percent(high.subtract(low), prev), SCALE_PCT));
        }
        return result;
    }

    /**
     * 对应 Python 的 {@code Decimal(str(value))}：解析失败或不有限都退化为 null。
     */
    static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal exact) {
            return exact;
        }
        try {
            // 与 Python 的 str(value) 对齐：Number 走 toString，字符串按字面解析，
            // Boolean/Map/List 会解析失败 → null（Python 侧是 InvalidOperation → None）。
            // "NaN"/"Infinity" 同样解析失败 → null（Python 侧 is_finite() 为假 → None）。
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 对应 Python 的 {@code format(value.quantize(quantum, ROUND_HALF_UP), "f")}。
     */
    static String fmt(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 对应 Python 的 {@code _percent}：分母为 0 时返回 null（而不是抛异常）。
     */
    static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, DIVISION);
    }

    /**
     * 复刻 Python 的 {@code value or fallback} 的真值判断（按 JSON 反序列化后的取值）。
     *
     * <p>Python 里 None/0/0.0/""/[]/{} 与 False 都是假值，其余为真——包括 NaN（{@code nan != 0} 为真）。
     */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof BigDecimal exact) {
            return exact.signum() != 0;
        }
        if (value instanceof Number number) {
            // NaN 走这里：NaN != 0.0 为真，与 Python 一致
            return number.doubleValue() != 0.0;
        }
        if (value instanceof CharSequence text) {
            return text.length() > 0;
        }
        if (value instanceof Collection<?> items) {
            return !items.isEmpty();
        }
        if (value instanceof Map<?, ?> entries) {
            return !entries.isEmpty();
        }
        return true;
    }
}