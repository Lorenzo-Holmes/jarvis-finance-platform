package com.jarvis.research.market.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.config.JarvisProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 腾讯 Provider 的契约与解析测试。
 *
 * 全部不联网：只覆盖注册元数据（市场/优先级/熔断键）与字段解析。
 * 解析问题是本次「从 MarketDataService 抽 Provider」最容易悄悄改坏的部分——
 * 抽错一个下标不会报错，只会让盘面数字变成别的数字。
 */
class TencentMarketDataProviderTest {

    private final TencentMarketDataProvider provider =
            new TencentMarketDataProvider(new JarvisProperties(), new ObjectMapper());

    // ==================== 注册元数据 ====================

    @Test
    void declaresItselfAsThePrimarySourceForBothCoreMarkets() {
        assertEquals("Tencent", provider.name());
        assertEquals("Tencent", provider.displayName());
        assertEquals(10, provider.priority());
        assertTrue(provider.supports("gold_etf"));
        assertTrue(provider.supports("london_gold"));
        assertFalse(provider.supports("crypto"));
        assertFalse(provider.supports("us_stock"));
    }

    @Test
    void keepsTheCircuitBreakerKeysOperatorsAlreadyDependOn() {
        // 这两个键在重构前就被 MarketSourceCircuitBreaker 使用，改名会打断现有告警与看板。
        assertEquals("core.tencent.etf", provider.sourceKey("gold_etf"));
        assertEquals("core.tencent.london", provider.sourceKey("london_gold"));
    }

    @Test
    void providesRealtimeQuoteForLondonGoldButNotItsDailyKline() {
        // 伦敦金实时走腾讯，日K来自新浪；两边能力不同，必须分别声明。
        assertTrue(provider.supportsQuote("london_gold"));
        assertTrue(provider.supportsQuote("gold_etf"));
        assertTrue(provider.supportsKline("gold_etf"));
        assertFalse(provider.supportsKline("london_gold"));
    }

    // ==================== ETF 解析 ====================

    private static String etfPayload() {
        String[] fields = new String[35];
        Arrays.fill(fields, "0");
        fields[1] = "黄金ETF华夏";
        fields[3] = "10.55";
        fields[4] = "10.40";
        fields[5] = "10.42";
        fields[30] = "20260916103000";
        fields[31] = "0.15";
        fields[32] = "1.44";
        fields[33] = "10.60";
        fields[34] = "10.38";
        return String.join("~", fields);
    }

    @Test
    void mapsEtfFieldsToTheSameSlotsAsTheOriginalImplementation() {
        Map<String, Object> quote = provider.parseEtf("sh518850", etfPayload());

        assertEquals("sh518850", quote.get("symbol"));
        assertEquals("黄金ETF华夏", quote.get("name"));
        assertEquals(10.55, quote.get("price"));
        assertEquals(10.40, quote.get("prev_close"));
        assertEquals(10.42, quote.get("open"));
        assertEquals(0.15, quote.get("change"));
        assertEquals(1.44, quote.get("change_pct"));
        assertEquals(10.60, quote.get("high"));
        assertEquals(10.38, quote.get("low"));
        assertEquals("20260916103000", quote.get("source_quote_time"));
    }

    @Test
    void fallsBackToSymbolWhenEtfNameIsMissing() {
        assertEquals("sh518850", provider.parseEtf("sh518850", "only-one-field").get("name"));
    }

    // ==================== 伦敦金解析 ====================

    private static String londonPayload() {
        // [0]现价 [1]涨跌 [2]今开 [3]昨收 [4]最高 [5]最低 [6]时间 [13]名称
        return "2650.5,12.3,2640.0,2638.2,2660.1,2635.0,2026-09-16 10:00:00,"
                + "x,x,x,x,x,x,伦敦金";
    }

    @Test
    void mapsLondonGoldFieldsToTheSameSlotsAsTheOriginalImplementation() {
        Map<String, Object> quote = provider.parseLondonGold(londonPayload());

        assertEquals("hf_XAU", quote.get("symbol"));
        assertEquals("伦敦金", quote.get("name"));
        assertEquals(2650.5, quote.get("price"));
        assertEquals(12.3, quote.get("change"));
        assertEquals(2640.0, quote.get("open"));
        assertEquals(2638.2, quote.get("prev_close"));
        assertEquals(2660.1, quote.get("high"));
        assertEquals(2635.0, quote.get("low"));
        assertEquals("2026-09-16 10:00:00", quote.get("source_quote_time"));
    }

    @Test
    void computesLondonGoldChangePctInsteadOfDroppingIt() {
        // 回归点：抽取 Provider 时曾漏掉 change_pct，导致伦敦金涨跌幅整列变空。
        Map<String, Object> quote = provider.parseLondonGold(londonPayload());

        double expected = (2650.5 - 2638.2) / 2638.2 * 100;
        assertEquals(expected, (Double) quote.get("change_pct"), 1e-9);
    }

    @Test
    void reportsZeroChangePctRatherThanDividingByZeroWhenPrevCloseIsMissing() {
        Map<String, Object> quote = provider.parseLondonGold(
                "2650.5,12.3,2640.0,0,2660.1,2635.0,2026-09-16 10:00:00,"
                        + "x,x,x,x,x,x,伦敦金");

        assertEquals(0.0, quote.get("change_pct"));
    }

    // ==================== null 与价格守卫 ====================

    @Test
    void unparseableFieldBecomesNullInsteadOfFakeZero() {
        // 回归点：抽取 Provider 时把「解析不出来」写成了 0，等于把缺失值伪装成真实价格。
        // persistQuotes 只拦截 price == null，一个 0 会被当成有效行情落库。
        String[] fields = {"a", "b", "c", "not-a-number", "10.0"};

        assertNull(provider.number(fields, 3));
        assertNull(provider.number(fields, 99));
        assertEquals(10.0, provider.number(fields, 4));
    }

    @Test
    void blankFieldBecomesNull() {
        assertNull(provider.number(new String[]{"", "  "}, 0));
        assertNull(provider.number(new String[]{"", "  "}, 1));
    }

    @Test
    void rejectsQuotesWithoutAUsablePrice() {
        assertTrue(provider.requirePrice(Map.of("error", "x")).containsKey("error"));
        assertTrue(provider.requirePrice(Map.of()).containsKey("error"));
        assertTrue(provider.requirePrice(new java.util.LinkedHashMap<>() {{
            put("price", null);
        }}).containsKey("error"));
        assertTrue(provider.requirePrice(Map.of("price", 0.0)).containsKey("error"));
        assertTrue(provider.requirePrice(Map.of("price", -1.0)).containsKey("error"));
        assertFalse(provider.requirePrice(Map.of("price", 10.5)).containsKey("error"));
    }

    // ==================== K线窗口 ====================

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(Map.of("date", "2026-01-" + String.format("%02d", i + 1), "close", (double) i));
        }
        return out;
    }

    @Test
    void tailKeepsTheMostRecentBarsInAscendingOrder() {
        List<Map<String, Object>> limited = provider.tail(rows(5), 2);

        assertEquals(2, limited.size());
        assertEquals("2026-01-04", limited.get(0).get("date"));
        assertEquals("2026-01-05", limited.get(1).get("date"));
    }

    @Test
    void tailIsANoOpWhenLimitIsAbsentOrLargerThanTheData() {
        assertEquals(5, provider.tail(rows(5), 0).size());
        assertEquals(5, provider.tail(rows(5), 500).size());
    }
}