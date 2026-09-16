package com.jarvis.research.market.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProviderRegistry 的解析规则测试。
 *
 * 重点是**降级链的确定性**：业务层按链序做故障切换，链序一旦随 Spring 注入顺序
 * 漂移，降级行为就不可复现。因此这里钉住「按 priority 升序、同优先级按 name」。
 */
class ProviderRegistryTest {

    /** 只关心排序与能力过滤的测试 Provider。 */
    private record Stub(String name,
                        List<String> markets,
                        int priority,
                        boolean quoteCapable,
                        boolean klineCapable) implements MarketDataProvider {

        @Override
        public boolean supports(String market) {
            return markets.contains(market);
        }

        @Override
        public Map<String, Object> quote(String symbol) {
            return Map.of("price", 1.0);
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
        public boolean supportsQuote(String market) {
            return quoteCapable;
        }

        @Override
        public boolean supportsKline(String market) {
            return klineCapable;
        }
    }

    private static Stub stub(String name, int priority) {
        return new Stub(name, List.of("gold_etf"), priority, true, true);
    }

    private static List<String> names(List<MarketDataProvider> providers) {
        return providers.stream().map(MarketDataProvider::name).toList();
    }

    @Test
    void ordersTheFallbackChainByAscendingPriority() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                stub("third", 30), stub("first", 10), stub("second", 20)));

        assertEquals(List.of("first", "second", "third"), names(registry.chain("gold_etf")));
        assertEquals("first", registry.find("gold_etf").name());
    }

    @Test
    void breaksPriorityTiesByNameSoTheChainIsDeterministic() {
        // 同优先级时若依赖注入顺序，链序会在不同启动间变化。
        ProviderRegistry registry = new ProviderRegistry(List.of(
                stub("Zeta", 10), stub("Alpha", 10)));
        ProviderRegistry reversed = new ProviderRegistry(List.of(
                stub("Alpha", 10), stub("Zeta", 10)));

        assertEquals(List.of("Alpha", "Zeta"), names(registry.chain("gold_etf")));
        assertEquals(names(registry.chain("gold_etf")), names(reversed.chain("gold_etf")));
    }

    @Test
    void excludesProvidersThatDoNotSupportTheMarket() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new Stub("other", List.of("us_stock"), 10, true, true),
                stub("gold", 20)));

        assertEquals(List.of("gold"), names(registry.chain("gold_etf")));
    }

    @Test
    void normalizesMarketBeforeMatchingSoCallersNeedNotPreCleanInput() {
        ProviderRegistry registry = new ProviderRegistry(List.of(stub("gold", 10)));

        assertEquals(List.of("gold"), names(registry.chain("  GOLD_ETF  ")));
    }

    @Test
    void quoteChainDropsKlineOnlyProviders() {
        // 新浪只提供伦敦金日K，不应出现在实时行情降级链里，否则每次报价都白记一次失败。
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new Stub("quote-and-kline", List.of("gold_etf"), 10, true, true),
                new Stub("kline-only", List.of("gold_etf"), 20, false, true)));

        assertEquals(List.of("quote-and-kline"), names(registry.quoteChain("gold_etf")));
    }

    @Test
    void klineChainDropsProvidersWithoutKlineSupport() {
        // 伦敦金实时走腾讯，但腾讯不提供它的日K，于是K线链要顺延到新浪。
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new Stub("quote-only", List.of("gold_etf"), 10, true, false),
                new Stub("kline-provider", List.of("gold_etf"), 20, true, true)));

        assertEquals(List.of("kline-provider"), names(registry.klineChain("gold_etf")));
    }

    @Test
    void unknownMarketYieldsEmptyChainInsteadOfThrowing() {
        ProviderRegistry registry = new ProviderRegistry(List.of(stub("gold", 10)));

        assertTrue(registry.chain("crypto").isEmpty());
        assertTrue(registry.quoteChain("crypto").isEmpty());
        assertTrue(registry.klineChain("crypto").isEmpty());
        assertTrue(registry.findOptional("crypto").isEmpty());
    }

    @Test
    void nullMarketIsHandledWithoutThrowing() {
        ProviderRegistry registry = new ProviderRegistry(List.of(stub("gold", 10)));

        assertTrue(registry.chain(null).isEmpty());
        assertTrue(registry.findOptional(null).isEmpty());
    }

    @Test
    void findReportsTheMarketWhenNothingSupportsIt() {
        ProviderRegistry registry = new ProviderRegistry(List.of(stub("gold", 10)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.find("crypto"));
        assertTrue(error.getMessage().contains("crypto"),
                "异常信息应带上市场名，便于定位配置缺失: " + error.getMessage());
    }

    @Test
    void reportsHowManyProvidersAreRegistered() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                stub("a", 10), stub("b", 20)));

        assertEquals(2, registry.size());
        assertTrue(new ProviderRegistry(List.of()).chain("gold_etf").isEmpty());
    }
}