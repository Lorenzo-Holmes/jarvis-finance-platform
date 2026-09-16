package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import com.jarvis.research.market.provider.MarketDataProvider;
import com.jarvis.research.market.provider.ProviderRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 美股与加密货币报价改由 {@link ProviderRegistry} 驱动后的契约测试（②③）。
 *
 * <p>与 A股（{@code ExtendedAShareQuoteTest}）最大的不同是 {@code quote_time} 的来源：
 * A股的报价时间由服务层补本地时间，而**美股与加密货币的时间是行情源自己的市场时间**
 * （Yahoo {@code regularMarketTime} / Binance {@code closeTime}，{@code Instant} 形态）。
 * 这个差别在切换时被写错过一次——服务层无条件覆盖 quote_time，会把"上游成交时刻"
 * 换成"我们取数的时刻"，而且悄悄带上时区——被 A股那条契约测试当场拦下。
 * 这个类里的 {@code quoteTimeComesFromTheProviderNotFromLocalNow} 就是补上的正面钉子。</p>
 */
class ExtendedUsStockAndCryptoQuoteTest {

    // ==================== 美股 ====================

    @Test
    void usStockEnvelopeKeepsTheSameKeysAsTheInlineImplementation() {
        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, usStockFields()));

        Map<String, Object> quote = service.quote("us_stock", "AAPL");

        // 注意**没有** open/high/low：Yahoo 的 chart.meta 不提供这些，原实现也不产出。
        assertEquals(new TreeSet<>(Set.of(
                        "market", "symbol", "name", "currency", "source", "quote_time",
                        "price", "prev_close", "change", "change_pct")),
                new TreeSet<>(quote.keySet()));
    }

    @Test
    void usStockEnvelopeKeepsTheSameValuesForEveryField() {
        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, usStockFields()));

        Map<String, Object> quote = service.quote("us_stock", "AAPL");

        assertEquals("us_stock", quote.get("market"));
        assertEquals("AAPL", quote.get("symbol"));
        assertEquals("Apple Inc.", quote.get("name"));
        assertEquals("USD", quote.get("currency"));
        assertEquals("Yahoo Finance", quote.get("source"), "主源用标的登记名");
        assertEquals(180.5, quote.get("price"));
        assertEquals(178.0, quote.get("prev_close"));
        assertEquals(2.5, quote.get("change"));
        assertEquals(1.40, quote.get("change_pct"));
    }

    /**
     * 美股只有 Yahoo 一个源，所以链长为 1，标签永远是标的登记名而非备用标签。
     */
    @Test
    void usStockHasASingleSourceSoItUsesTheDeclaredLabel() {
        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, usStockFields()));

        assertEquals("Yahoo Finance", service.quote("us_stock", "AAPL").get("source"));
    }

    @Test
    void usStockFailureYieldsBadGateway() {
        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, null));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.quote("us_stock", "AAPL"));

        assertEquals(502, error.getStatusCode().value());
    }

    // ==================== quote_time 的来源（本次被写错的地方）====================

    /**
     * **正面钉子**：Provider 给了 quote_time 就用它的，服务层不得覆盖。
     *
     * <p>这是"上游成交时刻"与"我们取数时刻"的区别，也是时区格式的区别。
     * 服务层如果无条件覆盖，用户看到的时间会变味，而且不会报任何错。</p>
     */
    @Test
    void quoteTimeComesFromTheProviderNotFromLocalNow() {
        Map<String, Object> fields = usStockFields();
        fields.put("quote_time", "2026-09-16T07:30:00Z");

        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, fields));

        assertEquals("2026-09-16T07:30:00Z", service.quote("us_stock", "AAPL").get("quote_time"),
                "行情源的市场时间必须原样保留");
    }

    /**
     * 反面：Provider 没给 quote_time（腾讯 A股口径就是如此）时，由服务层补**本地**时间。
     *
     * <p>补出来的必须是 {@code LocalDateTime.toString()} 那种不带时区的形式，
     * 不是 quoteBase 里那个带时区的占位值——这一点正是被 A股契约测试拦下的那个 bug。</p>
     */
    @Test
    void providerWithoutQuoteTimeGetsAPlainLocalTimestamp() {
        Map<String, Object> fields = usStockFields();
        fields.remove("quote_time");

        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, fields));

        String quoteTime = String.valueOf(service.quote("us_stock", "AAPL").get("quote_time"));

        assertTrue(quoteTime.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?"),
                "应为不带时区的 LocalDateTime 形式，实际: " + quoteTime);
        assertFalse(quoteTime.contains("+"), "不该带偏移量");
        assertFalse(quoteTime.contains("["), "更不该带时区名");
    }

    // ==================== 加密货币 ====================

    @Test
    void cryptoEnvelopeKeepsItsOhlcKeysFromTheInlineImplementation() {
        ExtendedMarketDataService service = serviceWith(
                stub("Binance", "crypto", "extended.binance", 10, cryptoFields()));

        Map<String, Object> quote = service.quote("crypto", "BTCUSDT");

        assertEquals(new TreeSet<>(Set.of(
                        "market", "symbol", "name", "currency", "source", "quote_time",
                        "price", "prev_close", "change", "change_pct", "open", "high", "low")),
                new TreeSet<>(quote.keySet()));
        assertEquals(65000.0, quote.get("open"));
        assertEquals(66000.0, quote.get("high"));
        assertEquals(64000.0, quote.get("low"));
    }

    @Test
    void cryptoPrimaryUsesBinanceAndItsDeclaredLabel() {
        ExtendedMarketDataService service = serviceWith(
                stub("Binance", "crypto", "extended.binance", 10, cryptoFields()),
                stub("Yahoo", "crypto", "extended.yahoo.crypto", 20, usStockFields()));

        Map<String, Object> quote = service.quote("crypto", "BTCUSDT");

        assertEquals("Binance", quote.get("source"));
        assertEquals(65000.0, quote.get("price"));
    }

    @Test
    void cryptoFallbackUsesTheYahooSourceAndTheExplicitFallbackLabel() {
        ExtendedMarketDataService service = serviceWith(
                stub("Binance", "crypto", "extended.binance", 10, null),
                stub("Yahoo", "crypto", "extended.yahoo.crypto", 20, usStockFields()));

        Map<String, Object> quote = service.quote("crypto", "BTCUSDT");

        assertEquals("Yahoo Finance (fallback)", quote.get("source"));
        assertEquals(180.5, quote.get("price"), "备用源的数据要真的用上");
    }

    /**
     * 加密货币备用源的熔断键必须是 {@code extended.yahoo.crypto}。
     *
     * <p>Yahoo 的 {@code sourceKey} 默认推导会给出 {@code core.yahoo.crypto}，
     * 而既有运维键是 {@code extended.yahoo.*} 一族——用错不会报错，
     * 只会让现有告警与看板突然查不到数。</p>
     */
    @Test
    void cryptoFallbackRecordsTheExtendedYahooCircuitKey() {
        MarketSourceCircuitBreaker breaker = org.mockito.Mockito.mock(MarketSourceCircuitBreaker.class);
        org.mockito.Mockito.when(breaker.allowRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(
                        stub("Binance", "crypto", "extended.binance", 10, null),
                        stub("Yahoo", "crypto", "extended.yahoo.crypto", 20, usStockFields()))),
                WebClient.builder().build());

        service.quote("crypto", "BTCUSDT");

        org.mockito.Mockito.verify(breaker).recordFailure("extended.binance");
        org.mockito.Mockito.verify(breaker).recordSuccess("extended.yahoo.crypto");
        org.mockito.Mockito.verify(breaker, org.mockito.Mockito.never())
                .recordSuccess("core.yahoo.crypto");
    }

    // ==================== name 语义 ====================

    @Test
    void providerSuppliedNameOverridesTheInstrumentName() {
        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, usStockFields()));

        assertEquals("Apple Inc.", service.quote("us_stock", "AAPL").get("name"));
    }

    /** Provider 不产出 name（上游名称为空白）时回落到标的登记名。 */
    @Test
    void omittedProviderNameFallsBackToTheInstrumentName() {
        Map<String, Object> fields = usStockFields();
        fields.remove("name");

        ExtendedMarketDataService service = serviceWith(
                stub("Yahoo", "us_stock", "extended.yahoo.stock", 20, fields));

        assertEquals("Apple", service.quote("us_stock", "AAPL").get("name"));
    }

    // ==================== 结构性钉子 ====================

    /**
     * 五个内联方法必须真的删掉，而**K线相关的那几个必须留下**。
     *
     * <p>后半句同样重要：{@code klineYahoo} / {@code yahooResult} / {@code aggregateCandles}
     * 还被加密货币K线备用源共用，本次没有迁移 K 线，删掉会直接打断它。</p>
     */
    @Test
    void inlineQuoteImplementationsAreGoneButKlineHelpersAreDeliberatelyKept() {
        Set<String> methods = java.util.Arrays.stream(ExtendedMarketDataService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(methods.contains("quoteYahooWithCircuit"), "美股内联报价应为已删除");
        assertFalse(methods.contains("quoteCryptoWithFallback"), "加密货币内联降级应为已删除");
        assertFalse(methods.contains("quoteBinance"), "Binance 内联报价应为已删除");
        assertTrue(methods.contains("registryQuote"), "取而代之的是注册表驱动的取数");

        assertTrue(methods.contains("klineYahoo"), "美股K线尚未迁移，必须保留");
        assertTrue(methods.contains("yahooResult"), "加密货币K线备用源仍在用它");
        assertTrue(methods.contains("aggregateCandles"), "同一个原因");
    }

    // ==================== 夹具 ====================

    /** Yahoo chart.meta 路径的产出（注意没有 open/high/low）。 */
    private static Map<String, Object> usStockFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("symbol", "AAPL");
        fields.put("name", "Apple Inc.");
        fields.put("price", 180.5);
        fields.put("prev_close", 178.0);
        fields.put("change", 2.5);
        fields.put("change_pct", 1.40);
        fields.put("quote_time", "2026-09-16T07:30:00Z");
        return fields;
    }

    /** Binance 24hr ticker 路径的产出。 */
    private static Map<String, Object> cryptoFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("market", "crypto");
        fields.put("symbol", "BTCUSDT");
        fields.put("name", "Bitcoin");
        fields.put("currency", "USDT");
        fields.put("price", 65000.0);
        fields.put("prev_close", 64500.0);
        fields.put("change", 500.0);
        fields.put("change_pct", 0.78);
        fields.put("open", 65000.0);
        fields.put("high", 66000.0);
        fields.put("low", 64000.0);
        fields.put("quote_time", "2026-09-16T07:30:00Z");
        return fields;
    }

    private static ExtendedMarketDataService serviceWith(MarketDataProvider... providers) {
        return new ExtendedMarketDataService(new ObjectMapper(), null,
                new ProviderRegistry(List.of(providers)),
                WebClient.builder().build());
    }

    /** {@code payload == null} 表示这个源失败。 */
    private static MarketDataProvider stub(String name, String market, String sourceKey,
                                           int priority, Map<String, Object> payload) {
        return new MarketDataProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(String candidate) {
                return market.equalsIgnoreCase(candidate);
            }

            @Override
            public Map<String, Object> quote(String symbol) {
                return payload == null ? Map.of("error", "stub 上游失败") : payload;
            }

            @Override
            public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
                return List.of();
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String sourceKey(String candidate) {
                return sourceKey;
            }
        };
    }
}