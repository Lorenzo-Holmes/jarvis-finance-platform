package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void reportsCryptoAsAlwaysOpen() {
        var status = service.session("crypto");
        assertEquals("open", status.get("status"));
        assertEquals(true, status.get("is_open"));
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

    @Test
    void switchesToEastmoneyAndStopsCallingTencentAfterCircuitOpens() {
        AtomicInteger tencentCalls = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if ("qt.gtimg.cn".equals(request.url().getHost())) {
                tencentCalls.incrementAndGet();
                return Mono.error(new IllegalStateException("injected Tencent failure"));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"data\":{\"f43\":123000,\"f60\":122000,\"f58\":\"测试标的\",\"f169\":1000,\"f170\":82,\"f46\":122000,\"f44\":124000,\"f45\":121000}}")
                    .build());
        }).build();
        MarketSourceCircuitBreaker breaker = new MarketSourceCircuitBreaker(
                new SimpleMeterRegistry(), 1, Duration.ofHours(1), Clock.systemUTC());
        ExtendedMarketDataService isolated = new ExtendedMarketDataService(new ObjectMapper(), breaker, client);

        Map<String, Object> first = isolated.quote("a_share", "sh600519");
        Map<String, Object> second = isolated.quote("a_share", "sh600520");

        assertEquals("EastMoney (fallback)", first.get("source"));
        assertEquals("EastMoney (fallback)", second.get("source"));
        assertEquals(1, tencentCalls.get(), "Tencent 熔断后不应再次被调用");
        assertEquals("OPEN", breaker.state("extended.tencent.stock"));
    }

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
