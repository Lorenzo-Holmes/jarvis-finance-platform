package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.market.dto.MarketStatusDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtendedMarketDataServiceTest {

    private final ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper());

    @Test
    void exposesAllowListedAUsAndCryptoInstruments() {
        var instruments = service.listInstruments();
        assertFalse(instruments.isEmpty());
        assertEquals(10, instruments.size());
        assertEquals("a_share", instruments.get(0).get("market"));
    }

    @Test
    void rejectsUnknownSymbolBeforeCallingExternalSource() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.quote("us_stock", "NOT_ALLOWED"));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void rejectsMalformedUserEnteredSymbols() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.resolveInstrument("a_share", "贵州茅台")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.resolveInstrument("us_stock", "AAPL/1")).getStatusCode().value());
    }

    @Test
    void globalIndexResolverUsesAnExplicitAllowList() {
        Map<String, Object> dow = service.resolveInstrument("global_index", "^DJI");
        Map<String, Object> tech = service.resolveInstrument("global_index", "HSTECH.HK");

        assertEquals("global_index", dow.get("market"));
        assertEquals("^DJI", dow.get("symbol"));
        assertEquals("道琼斯", dow.get("name"));
        assertEquals("HSTECH.HK", tech.get("symbol"));
        assertEquals("恒生科技指数", tech.get("name"));

        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> service.resolveInstrument("global_index", "^RUT"));
        assertEquals(400, invalid.getStatusCode().value());
    }

    @Test
    void overviewKeepsAllFourteenSlotsWhenMostUpstreamsAreDown() {
        String html = """
                <html><body><table>
                <tr><td>2026-09-18</td><td>Au99.99</td><td>937.00</td><td>948.90</td><td>935.50</td><td>947.09</td><td>12.28</td><td>1.31%</td></tr>
                </table></body></html>
                """;
        WebClient sgeClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(html)
                        .build()))
                .build();
        ExtendedMarketDataService isolated = new ExtendedMarketDataService(new ObjectMapper(), null, sgeClient);

        List<Map<String, Object>> overview = isolated.marketOverview();

        assertEquals(14, overview.size());
        assertEquals(List.of("sse", "chinext", "star50", "szse", "bse50", "sse50",
                        "dow", "nasdaq", "sp500", "nasdaq100", "au9999", "hsi", "hscei", "hstech"),
                overview.stream().map(item -> String.valueOf(item.get("key"))).toList());
        Map<String, Object> gold = overview.get(10);
        assertEquals(true, gold.get("available"));
        assertEquals("Au99.99", gold.get("symbol"));
        assertEquals(947.09, gold.get("price"));
        assertEquals(1.31, gold.get("change_pct"));
        assertEquals("上海黄金交易所（日行情）", gold.get("source"));
        assertEquals(false, overview.get(0).get("available"), "A股源故障只能降级单卡，不能让 overview 失败");
        assertEquals(false, overview.get(6).get("available"), "全球指数源故障同样只降级单卡");
    }

    @Test
    void reportsCryptoAsAlwaysOpen() {
        MarketStatusDTO status = service.session("crypto");
        assertEquals("open", status.status());
        assertEquals(true, status.isOpen());
        assertEquals("crypto", status.market());
    }

    @Test
    void rejectsInvalidKlineLimit() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.kline("crypto", "BTCUSDT", "1d", 501));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void shortQuoteCacheIsHardBoundedForUntrustedCustomSymbols() {
        long now = System.nanoTime();
        for (int i = 0; i < 2_200; i++) {
            ReflectionTestUtils.invokeMethod(service, "cacheQuote",
                    "us_stock:SYM" + i, Map.of("price", 100 + i), now);
        }

        assertEquals(2_000, service.quoteCacheSize());
    }

    @Test
    void computesTechnicalIndicatorsAndSummaryFromKlineRows() {
        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double close = 100 + i * 0.8 + (i % 4) * 0.2;
            rows.add(new LinkedHashMap<>(java.util.Map.of(
                    "date", "2026-01-" + String.format("%02d", i + 1),
                    "open", close - 0.5,
                    "close", close,
                    "high", close + 1,
                    "low", close - 1,
                    "volume", 1000.0)));
        }

        var summary = service.enrichTechnicalIndicators(rows);

        assertEquals("ok", summary.get("status"));
        assertNotNull(rows.get(39).get("sma20"));
        assertNotNull(rows.get(39).get("rsi14"));
        assertNotNull(rows.get(39).get("macd"));
        assertNotNull(rows.get(39).get("bollinger_upper"));
        assertNotNull(rows.get(39).get("atr14"));
        assertNotNull(rows.get(39).get("stoch_k14"));
        assertNotNull(rows.get(39).get("adx14"));
        assertNotNull(summary.get("indicators"));
        assertNotNull(summary.get("support_20"));
        assertNotNull(summary.get("resistance_20"));
    }

    // 「切到 EastMoney、熔断后不再调用 Tencent」这条测试已迁往
    // ExtendedAShareQuoteTest#opensThePrimaryCircuitAndStopsCallingItOnTheNextRequest。
    // A股报价改由 ProviderRegistry 驱动后，该行为不再住在内联实现里，
    // 靠伪造 HTTP 响应已经驱动不到它——改由 stub Provider + 真实熔断器表达，断言逐条保留。

    @Test
    void returnsPersistedQuoteWhenAllUpstreamSourcesFail() throws Exception {
        MarketDataCacheRepository cacheRepository = mock(MarketDataCacheRepository.class);
        MarketDataCache cached = MarketDataCache.builder()
                .cacheKey("quote:us_stock:AAPL")
                .kind("quote")
                .market("us_stock")
                .symbol("AAPL")
                .payload(new ObjectMapper().writeValueAsString(Map.of(
                        "market", "us_stock", "symbol", "AAPL", "price", 180.0)))
                .source("Yahoo Finance")
                .updatedAt(LocalDateTime.now().minusMinutes(2))
                .build();
        when(cacheRepository.findByCacheKey("quote:us_stock:AAPL")).thenReturn(Optional.of(cached));
        WebClient failingClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("injected upstream failure")))
                .build();

        ExtendedMarketDataService isolated = new ExtendedMarketDataService(
                new ObjectMapper(), null, cacheRepository, failingClient);

        Map<String, Object> result = isolated.quote("us_stock", "AAPL");

        assertEquals(180.0, result.get("price"));
        assertEquals(true, result.get("stale"));
        assertEquals("Yahoo Finance (local-cache)", result.get("source"));
    }

    @Test
    void returnsPersistedKlineWhenUpstreamFails() throws Exception {
        MarketDataCacheRepository cacheRepository = mock(MarketDataCacheRepository.class);
        List<Map<String, Object>> rows = List.of(new LinkedHashMap<>(Map.of(
                "date", "2026-09-08T10:00:00Z",
                "open", 179.0,
                "close", 180.0,
                "high", 181.0,
                "low", 178.0,
                "volume", 1000.0)));
        MarketDataCache cached = MarketDataCache.builder()
                .cacheKey("kline:us_stock:AAPL:1d")
                .kind("kline")
                .market("us_stock")
                .symbol("AAPL")
                .interval("1d")
                .payload(new ObjectMapper().writeValueAsString(rows))
                .source("Yahoo Finance")
                .updatedAt(LocalDateTime.now().minusMinutes(2))
                .build();
        when(cacheRepository.findByCacheKey("kline:us_stock:AAPL:1d")).thenReturn(Optional.of(cached));
        WebClient failingClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("injected upstream failure")))
                .build();

        ExtendedMarketDataService isolated = new ExtendedMarketDataService(
                new ObjectMapper(), null, cacheRepository, failingClient);

        Map<String, Object> result = isolated.kline("us_stock", "AAPL", "1d", 20);

        assertEquals(true, result.get("stale"));
        assertEquals("local-cache", result.get("source"));
        assertEquals(1, result.get("count"));
    }
}
