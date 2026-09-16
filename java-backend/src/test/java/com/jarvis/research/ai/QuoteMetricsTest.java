package com.jarvis.research.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 ⑧：行情快照派生指标。
 *
 * <p>期望值全部**独立推导**（手算），不是从实现输出回抄。同一组字面量在
 * {@code backend/tests/test_quote_metrics_contract.py} 里被 Python 侧独立钉一遍，
 * 两端一致才算口径统一。
 */
class QuoteMetricsTest {

    private static Map<String, Object> snapshot(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("常规快照：金额 6 位、百分比 4 位，键顺序与 Python 一致")
    void typicalSnapshot() {
        Map<String, Object> result = QuoteMetrics.compute(snapshot(
                "price", 100.5,
                "prev_close", 100,
                "open", 99,
                "high", 102,
                "low", 98.5,
                "quote_time", "2026-01-02T10:00:00",
                "source", "tencent"));

        // 手算：change = 0.5；change_pct = 0.5*100/100 = 0.5；
        // vs_open = 1.5*100/99 = 1.515151... → 4 位 HALF_UP → 1.5152；
        // range = 3.5*100/100 = 3.5
        assertEquals(List.of("price", "prev_close", "open", "high", "low", "quote_time",
                "source", "change", "change_pct", "vs_open_pct", "intraday_range_pct"),
                new ArrayList<>(result.keySet()));
        assertEquals("100.500000", result.get("price"));
        assertEquals("100.000000", result.get("prev_close"));
        assertEquals("99.000000", result.get("open"));
        assertEquals("102.000000", result.get("high"));
        assertEquals("98.500000", result.get("low"));
        assertEquals("2026-01-02T10:00:00", result.get("quote_time"));
        assertEquals("tencent", result.get("source"));
        assertEquals("0.500000", result.get("change"));
        assertEquals("0.5000", result.get("change_pct"));
        assertEquals("1.5152", result.get("vs_open_pct"));
        assertEquals("3.5000", result.get("intraday_range_pct"));
    }

    @Test
    @DisplayName("prev_close 为数值 0 时按 Python 的 or 语义回退到 yesterday_price")
    void zeroPrevCloseFallsBackToYesterdayPrice() {
        // Python: `prev_close or yesterday_price` —— 数值 0 是假值，会走 yesterday_price=50。
        // 只判 null 的实现会得到 prev=0，进而 change/change_pct 全错，所以这条必须钉住。
        Map<String, Object> result = QuoteMetrics.compute(snapshot(
                "price", 55,
                "prev_close", 0,
                "yesterday_price", 50,
                "open", 54,
                "high", 56,
                "low", 53));

        // 手算：change = 5；change_pct = 5*100/50 = 10；
        // vs_open = 1*100/54 = 1.851851... → 1.8519；range = 3*100/50 = 6
        assertEquals("50.000000", result.get("prev_close"));
        assertEquals("5.000000", result.get("change"));
        assertEquals("10.0000", result.get("change_pct"));
        assertEquals("1.8519", result.get("vs_open_pct"));
        assertEquals("6.0000", result.get("intraday_range_pct"));
    }

    @Test
    @DisplayName("prev_close 为 0 且无 yesterday_price：键在但值为 null，依赖项整键缺失")
    void missingPrevCloseDropsDependentKeys() {
        Map<String, Object> result = QuoteMetrics.compute(snapshot(
                "price", 10,
                "prev_close", 0,
                "open", 9,
                "high", 11,
                "low", 8.5));

        assertNull(result.get("prev_close"));
        assertTrue(result.containsKey("prev_close"), "键必须存在、值为 null（不是省略键）");
        assertFalse(result.containsKey("change"), "prev 为 null 时不应出现 change");
        assertFalse(result.containsKey("change_pct"));
        assertFalse(result.containsKey("intraday_range_pct"), "range 同样依赖 prev");
        // vs_open 只依赖 price/open：1*100/9 = 11.1111...→ 11.1111
        assertEquals("11.1111", result.get("vs_open_pct"));
    }

    @Test
    @DisplayName("分母为 0 时百分比取 null 而不是抛异常")
    void zeroDenominatorYieldsNull() {
        Map<String, Object> result = QuoteMetrics.compute(snapshot(
                "price", 10,
                "prev_close", 10,
                "open", 0,
                "high", 12,
                "low", 11));

        // 手算：change = 0 → "0.000000"；change_pct = 0*100/10 = 0 → "0.0000"；
        // vs_open 的分母是 0 → null（键在）；range = 1*100/10 = 10
        assertEquals("0.000000", result.get("change"));
        assertEquals("0.0000", result.get("change_pct"));
        assertTrue(result.containsKey("vs_open_pct"));
        assertNull(result.get("vs_open_pct"));
        assertEquals("10.0000", result.get("intraday_range_pct"));
    }

    @Test
    @DisplayName("不可解析的价格退化为 null，空串 quote_time 回退到 time，其余原样透传")
    void unparsablePriceAndEmptyQuoteTime() {
        Map<String, Object> result = QuoteMetrics.compute(snapshot(
                "price", "abc",
                "yesterday_price", "105",
                "quote_time", "",
                "time", "09:30"));

        assertTrue(result.containsKey("price"));
        assertNull(result.get("price"));
        assertEquals("105.000000", result.get("prev_close"), "字符串 \"105\" 应按字面解析");
        assertEquals("09:30", result.get("quote_time"), "空串是假值，应回退到 time");
        assertNull(result.get("source"));
        assertFalse(result.containsKey("change"), "price 为 null → 依赖项整键缺失");
        assertFalse(result.containsKey("vs_open_pct"));
        assertFalse(result.containsKey("intraday_range_pct"));
    }

    @Test
    @DisplayName("无输入时不抛异常，只给出 null 值的基础键")
    void emptyInputIsSafe() {
        Map<String, Object> result = QuoteMetrics.compute(null);

        assertEquals(List.of("price", "prev_close", "open", "high", "low", "quote_time", "source"),
                new ArrayList<>(result.keySet()));
        assertNull(result.get("price"));
        assertNull(result.get("quote_time"));
    }

    @Test
    @DisplayName("truthy 复刻 Python 的真值表（含 NaN 为真、\"0\" 为真）")
    void truthinessMatchesPython() {
        assertFalse(QuoteMetrics.truthy(null));
        assertFalse(QuoteMetrics.truthy(0));
        assertFalse(QuoteMetrics.truthy(0.0));
        assertFalse(QuoteMetrics.truthy(""));
        assertFalse(QuoteMetrics.truthy(false));
        assertFalse(QuoteMetrics.truthy(new ArrayList<>()));
        assertTrue(QuoteMetrics.truthy("0"), "非空字符串为真");
        assertTrue(QuoteMetrics.truthy(Double.NaN), "Python 里 nan != 0 为真");
        assertTrue(QuoteMetrics.truthy(1));
        assertTrue(QuoteMetrics.truthy(List.of(1)));
    }
}