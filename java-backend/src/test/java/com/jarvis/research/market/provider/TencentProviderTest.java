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

    // ==================== A股 ====================

    @Test
    void declaresAShareSupportAndItsOwnCircuitBreakerKey() {
        assertTrue(provider.supports("a_share"));
        // 扩展行情服务既有的熔断键，与 core.tencent.* 不是一族，不能改名。
        assertEquals("extended.tencent.stock", provider.sourceKey("a_share"));
        // K线尚未迁移：声明支持 A股报价不等于能做 A股K线。
        assertFalse(provider.supportsKline("a_share"));
        assertTrue(provider.supportsQuote("a_share"));
    }

    private static String aSharePayload() {
        String[] fields = new String[35];
        Arrays.fill(fields, "0");
        fields[1] = "贵州茅台";
        fields[3] = "1500.00";
        fields[4] = "1490.00";
        fields[5] = "1495.00";
        fields[30] = "20260916103000";
        fields[31] = "10.00";
        fields[32] = "0.67";
        fields[33] = "1510.00";
        fields[34] = "1488.00";
        return String.join("~", fields);
    }

    /**
     * A股与黄金ETF的字段下标完全相同，这条测试把 A股口径逐槽钉住。
     */
    @Test
    void mapsAShareFieldsToTheSameSlotsAsTheInlineImplementation() {
        Map<String, Object> quote = provider.parseAShare("sh600519", aSharePayload());

        assertEquals("sh600519", quote.get("symbol"));
        assertEquals("贵州茅台", quote.get("name"));
        assertEquals(1500.00, quote.get("price"));
        assertEquals(1490.00, quote.get("prev_close"));
        assertEquals(1495.00, quote.get("open"));
        assertEquals(10.00, quote.get("change"));
        assertEquals(0.67, quote.get("change_pct"));
        assertEquals(1510.00, quote.get("high"));
        assertEquals(1488.00, quote.get("low"));
    }

    /**
     * 这条是回归钉子：A股口径**不能**产出 {@code source_quote_time}。
     *
     * <p>同一个下标 30 在 ETF 口径里正是 {@code source_quote_time}，两个解析器因此长得很像，
     * 很容易被「顺手统一」成一个。一旦补上这个键，扩展行情信封就多了一个前端没有预期的字段，
     * 而且业务层会改用它去算 {@code quote_time}，报价时间随之变形。</p>
     */
    @Test
    void aSharePayloadDoesNotInventSourceQuoteTime() {
        Map<String, Object> quote = provider.parseAShare("sh600519", aSharePayload());

        assertFalse(quote.containsKey("source_quote_time"),
                "A股口径不读字段 30，键就不该出现");
        assertTrue(provider.parseEtf("sh518850", etfPayload()).containsKey("source_quote_time"),
                "而 ETF 口径照旧产出它");
    }

    @Test
    void omitsAShareNameRatherThanInventingOneWhenUpstreamLeavesItBlank() {
        String[] fields = new String[35];
        Arrays.fill(fields, "0");
        fields[1] = "   ";
        fields[3] = "10.00";

        Map<String, Object> quote = provider.parseAShare("sh600000", String.join("~", fields));

        assertFalse(quote.containsKey("name"),
                "字段 1 空白时应省略，由业务层回落到标的登记名");
    }

    /**
     * A股的价格守卫**只拦 null、不拦 0**，与 {@link TencentMarketDataProvider#requirePrice} 不同。
     *
     * <p>停牌的 A 股在腾讯接口里就是 {@code price = 0.00}。原 {@code quoteTencent} 只判
     * {@code price == null}，所以停牌股票能正常返回。若这里跟着 ETF 口径改成 {@code <= 0}，
     * 停牌标的会被腾讯拒绝、再被东方财富拒绝（后者本来就判 {@code <= 0}），
     * 整个 A股报价直接变成 502——这是行为回归。</p>
     */
    @Test
    void aSharePriceGuardAllowsZeroButRejectsMissingPrice() {
        assertFalse(provider.requireASharePrice(Map.of("price", 0.0)).containsKey("error"),
                "停牌股 price=0 必须放行");
        assertFalse(provider.requireASharePrice(Map.of("price", 15.5)).containsKey("error"));
        assertTrue(provider.requireASharePrice(new java.util.HashMap<>() {{
            put("price", null);
        }}).containsKey("error"), "价格缺失应报错");
        assertTrue(provider.requirePrice(Map.of("price", 0.0)).containsKey("error"),
                "对照：ETF 口径本来就更严格");
    }

    @Test
    void infersMarketFromSymbolOnlyAsAFallback() {
        assertEquals("london_gold", TencentMarketDataProvider.marketOf("hf_XAU"));
        assertEquals("gold_etf", TencentMarketDataProvider.marketOf("sh518850"));
        // sh518850 与 sh600519 形态相同，单参调用只能靠已知 ETF 代码区分。
        assertEquals("a_share", TencentMarketDataProvider.marketOf("sh600519"));
        assertEquals("a_share", TencentMarketDataProvider.marketOf("sz000001"));
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