package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import com.jarvis.research.market.provider.MarketDataProvider;
import com.jarvis.research.market.provider.ProviderRegistry;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A股**日K**改由 {@link ProviderRegistry} 驱动后的契约测试。
 *
 * <p>与报价切换（{@code ExtendedAShareQuoteTest}）并列，但有一处关键差别：
 * 报价把「返回 error Map」当失败，而 <b>K线把「返回空列表」当失败</b>。
 * 切换前的 {@code klineTencent} 无数据时抛异常、从而降级到东方财富；
 * Provider 按契约返回空列表，所以判定失败这件事落在服务层。
 * 如果漏了这一点，"A股K线为空"会被当成成功返回，降级永远不会发生，
 * 用户看到的是空图而不是备用源的数据。这个类里最重要的一条测试就在钉它。</p>
 */
class ExtendedAShareKlineTest {

    // ==================== 信封契约 ====================

    @Test
    void registryDrivenDailyKlineKeepsTheEnvelopeKeysOfTheInlineImplementation() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.kline", 10, rows("2026-09-15", "2026-09-16")));

        Map<String, Object> kline = service.kline("a_share", "sh600519", "1d", 20);

        assertEquals(new TreeSet<>(Set.of(
                        "market", "symbol", "interval", "count", "data", "analysis", "stale", "source", "range")),
                new TreeSet<>(kline.keySet()));
        assertEquals("a_share", kline.get("market"));
        assertEquals("sh600519", kline.get("symbol"));
        assertEquals("1d", kline.get("interval"));
        assertEquals(2, kline.get("count"));
        assertFalse((Boolean) kline.get("stale"));
        assertNotNull(kline.get("analysis"));
    }

    @Test
    void everyBarKeepsTheOhlcvKeysTheChartDependsOn() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.kline", 10, rows("2026-09-16")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) service
                .kline("a_share", "sh600519", "1d", 20).get("data");

        assertEquals(1, bars.size());
        Map<String, Object> bar = bars.get(0);
        assertEquals("2026-09-16", bar.get("date"));
        assertEquals(1500.00, bar.get("open"));
        assertEquals(1510.00, bar.get("close"));
        assertEquals(1520.00, bar.get("high"));
        assertEquals(1490.00, bar.get("low"));
        assertEquals(12345.0, bar.get("volume"));
    }

    @Test
    void rangeIsDerivedFromTheReturnedBars() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.kline", 10,
                        rows("2026-09-15", "2026-09-16", "2026-09-17")));

        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) service
                .kline("a_share", "sh600519", "1d", 20).get("range");

        assertEquals("2026-09-15", range.get("start"));
        assertEquals("2026-09-17", range.get("end"));
        assertEquals(3, range.get("count"));
    }

    // ==================== 空列表 = 失败 ====================

    /**
     * 最重要的一条：主源"成功地返回了空列表"仍必须降级。
     *
     * <p>Provider 契约里空列表是合法返回值（无数据），但对 A股日K 而言，
     * 切换前那种情况是抛异常并降级的。若这里把空当成功，用户会看到空图，
     * 而备用源明明有数据。</p>
     */
    @Test
    void emptyKlineFromThePrimaryStillFallsThroughToTheFallback() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.kline", 10, List.of()),
                new StubProvider("EastMoney", "extended.eastmoney.kline", 30, rows("2026-09-16")));

        Map<String, Object> kline = service.kline("a_share", "sh600519", "1d", 20);

        assertEquals(1, kline.get("count"), "应拿到备用源的数据，而不是主源的空列表");
        assertFalse((Boolean) kline.get("stale"));
    }

    @Test
    void everyKlineSourceEmptyYieldsBadGateway() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.kline", 10, List.of()),
                new StubProvider("EastMoney", "extended.eastmoney.kline", 30, List.of()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.kline("a_share", "sh600519", "1d", 20));

        assertEquals(502, error.getStatusCode().value());
    }

    @Test
    void missingRegistryFailsLoudlyInsteadOfReturningAnEmptyChart() {
        ExtendedMarketDataService noRegistry = new ExtendedMarketDataService(new ObjectMapper());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> noRegistry.kline("a_share", "sh600519", "1d", 20));

        assertEquals(502, error.getStatusCode().value());
    }

    // ==================== 熔断键与顺序 ====================

    @Test
    void usesTheKlineCircuitKeysNotTheQuoteKeys() {
        MarketSourceCircuitBreaker breaker = org.mockito.Mockito.mock(MarketSourceCircuitBreaker.class);
        org.mockito.Mockito.when(breaker.allowRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(
                        new StubProvider("Tencent", "extended.tencent.kline", 10, List.of()),
                        new StubProvider("EastMoney", "extended.eastmoney.kline", 30, rows("2026-09-16")))),
                WebClient.builder().build());

        service.kline("a_share", "sh600519", "1d", 20);

        org.mockito.Mockito.verify(breaker).recordFailure("extended.tencent.kline");
        org.mockito.Mockito.verify(breaker).recordSuccess("extended.eastmoney.kline");
        // 报价的键不该在K线链路上被碰到。
        org.mockito.Mockito.verify(breaker, org.mockito.Mockito.never())
                .recordFailure("extended.tencent.stock");
    }

    @Test
    void opensTheKlineCircuitAndStopsCallingThePrimaryOnTheNextRequest() {
        AtomicInteger tencentCalls = new AtomicInteger();
        MarketSourceCircuitBreaker breaker = new MarketSourceCircuitBreaker(
                new SimpleMeterRegistry(), 1, Duration.ofHours(1), Clock.systemUTC());
        ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(
                        new StubProvider("Tencent", "extended.tencent.kline", 10, List.of(), tencentCalls),
                        new StubProvider("EastMoney", "extended.eastmoney.kline", 30, rows("2026-09-16")))),
                WebClient.builder().build());

        service.kline("a_share", "sh600519", "1d", 20);
        service.kline("a_share", "sz000001", "1d", 20);

        assertEquals(1, tencentCalls.get(), "腾讯K线熔断后不应再次被调用");
        assertEquals("OPEN", breaker.state("extended.tencent.kline"));
    }

    @Test
    void klineChainOrderFollowsPriorityNotDeclarationOrder() {
        AtomicInteger tencentCalls = new AtomicInteger();
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("EastMoney", "extended.eastmoney.kline", 30, List.of()),
                new StubProvider("Tencent", "extended.tencent.kline", 10, rows("2026-09-16"), tencentCalls));

        service.kline("a_share", "sh600519", "1d", 20);

        assertEquals(1, tencentCalls.get(), "priority 最小的腾讯应先被尝试");
    }

    // ==================== 结构性钉子 ====================

    /**
     * A股K线的所有内联实现都必须删掉——日K和分钟级现在都走 Provider。
     *
     * <p>这条测试原本断言"分钟级（{@code klineTencentIntraday}）尚未迁移，必须保留"。
     * 分钟级后来迁走了，它按设计失败，于是翻转成现在这样。
     * 它的另一半价值仍在：{@code fetchKlineFrom} 与 {@code aggregateCandles}
     * 是**刻意留下**的（10m 是派生周期，不是来源能力），误删会打断分钟级聚合。</p>
     */
    @Test
    void everyInlineAShareKlineImplementationIsGone() {
        Set<String> methods = Arrays.stream(ExtendedMarketDataService.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertFalse(methods.contains("klineTencent"), "内联腾讯日K应为已删除");
        assertFalse(methods.contains("klineEastmoney"), "内联东方财富日K应为已删除");
        assertFalse(methods.contains("klineAShareWithFallback"), "内联日K降级应为已删除");
        // 分钟级：名字是错的（它打的其实是东方财富的接口），实现也已合并进 EastMoney Provider。
        assertFalse(methods.contains("klineTencentIntraday"), "内联A股分钟K应为已删除");
        assertFalse(methods.contains("eastmoneySecId"), "secid 拼装已搬进 Provider");

        // registryDailyKline 后来被合并成通用的 registryKline（美股/加密货币K线也迁进来了），
        // 所以这里断言的是新名字。
        assertTrue(methods.contains("registryKline"), "取而代之的是注册表驱动的K线取数");
        assertTrue(methods.contains("fetchKlineFrom"), "10m 聚合在这一层做，Provider 不声称支持派生周期");
        assertTrue(methods.contains("aggregateCandles"), "同上");
        assertTrue(methods.contains("tail"), "被 aggregateCandles 用着");
    }

    // ==================== 夹具 ====================

    private static List<Map<String, Object>> rows(String... dates) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String date : dates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("open", 1500.00);
            row.put("close", 1510.00);
            row.put("high", 1520.00);
            row.put("low", 1490.00);
            row.put("volume", 12345.0);
            rows.add(row);
        }
        return rows;
    }

    private static ExtendedMarketDataService serviceWith(MarketDataProvider... providers) {
        return new ExtendedMarketDataService(new ObjectMapper(), null,
                new ProviderRegistry(List.of(providers)),
                WebClient.builder().build());
    }

    /** {@code rows} 为空表示这个源"成功但没有数据"。 */
    private static final class StubProvider implements MarketDataProvider {

        private final String name;
        private final String klineSourceKey;
        private final int priority;
        private final List<Map<String, Object>> rows;
        private final AtomicInteger calls;

        StubProvider(String name, String klineSourceKey, int priority, List<Map<String, Object>> rows) {
            this(name, klineSourceKey, priority, rows, null);
        }

        StubProvider(String name, String klineSourceKey, int priority,
                     List<Map<String, Object>> rows, AtomicInteger calls) {
            this.name = name;
            this.klineSourceKey = klineSourceKey;
            this.priority = priority;
            this.rows = rows;
            this.calls = calls;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(String market) {
            return "a_share".equalsIgnoreCase(market);
        }

        @Override
        public Map<String, Object> quote(String symbol) {
            return Map.of("error", "本测试只关心K线");
        }

        @Override
        public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
            if (calls != null) {
                calls.incrementAndGet();
            }
            // 每次返回新副本：服务层会往里注入技术指标，共享同一份会互相污染。
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                copy.add(new LinkedHashMap<>(row));
            }
            return copy;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String sourceKey(String market) {
            return "stub.quote." + name;
        }

        @Override
        public String klineSourceKey(String market) {
            return klineSourceKey;
        }
    }
}