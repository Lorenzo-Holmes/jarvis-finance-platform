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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A股报价改由 {@link ProviderRegistry} 驱动后的**信封契约**测试。
 *
 * <p>切换前 A股报价由 {@code ExtendedMarketDataService} 内联实现（{@code quoteTencent} /
 * {@code quoteEastmoney}），本次改为走注册表。改的是取数路径，**不该改的**是对外信封：
 * 键集、{@code source} 标签、{@code quote_time} 形式、熔断键都必须与切换前一致。
 * 这个类就是把这些逐条钉住。</p>
 *
 * <p>为什么用 stub Provider 而不是 stub HTTP：Provider 自己用
 * {@code ExternalWebClients.create(...)} 建客户端，外部无法注入。
 * 而本次真正改动的东西——信封包装、降级顺序、熔断键、来源标签——恰好都在服务层，
 * 用 stub Provider 就能完整覆盖。字段解析的可信度由
 * {@code TencentMarketDataProviderTest#mapsAShareFieldsToTheSameSlotsAsTheInlineImplementation}
 * 与那条「A股不得凭空产出 source_quote_time」的回归钉子负责。</p>
 */
class ExtendedAShareQuoteTest {

    // ==================== 信封契约 ====================

    @Test
    void registryDrivenQuoteKeepsTheExactEnvelopeKeySetOfTheInlineImplementation() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", aShareFields()));

        Map<String, Object> quote = service.quote("a_share", "sh600519");

        // 切换前：quoteBase 的 6 个键 + 解析产出的 7 个新键 = 13
        assertEquals(new TreeSet<>(Set.of(
                        "market", "symbol", "name", "currency", "source", "quote_time",
                        "price", "prev_close", "open", "change", "change_pct", "high", "low")),
                new TreeSet<>(quote.keySet()));
    }

    @Test
    void registryDrivenQuoteKeepsTheSameValuesForEveryEnvelopeField() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", aShareFields()));

        Map<String, Object> quote = service.quote("a_share", "sh600519");

        assertEquals("a_share", quote.get("market"));
        assertEquals("sh600519", quote.get("symbol"));
        assertEquals("贵州茅台", quote.get("name"));
        assertEquals("CNY", quote.get("currency"));
        assertEquals(1500.00, quote.get("price"));
        assertEquals(1490.00, quote.get("prev_close"));
        assertEquals(1495.00, quote.get("open"));
        assertEquals(10.00, quote.get("change"));
        assertEquals(0.67, quote.get("change_pct"));
        assertEquals(1510.00, quote.get("high"));
        assertEquals(1488.00, quote.get("low"));
    }

    /**
     * {@code quote_time} 曾是两段式：quoteBase 先写带时区的形式，解析器最后用
     * {@code LocalDateTime.now().toString()} 覆盖掉。最终可见的是**不带时区**的那个。
     */
    @Test
    void quoteTimeIsPlainLocalDateTimeNotTheZonedQuoteBaseForm() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", aShareFields()));

        String quoteTime = String.valueOf(service.quote("a_share", "sh600519").get("quote_time"));

        assertTrue(quoteTime.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?"),
                "应为 LocalDateTime.toString() 形式，实际: " + quoteTime);
        assertFalse(quoteTime.contains("+"), "不该带偏移量");
        assertFalse(quoteTime.endsWith("Z"), "不该带 Z");
    }

    // ==================== 来源标签 ====================

    @Test
    void primarySourceKeepsTheInstrumentDeclaredLabel() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", aShareFields()));

        assertEquals("Tencent", service.quote("a_share", "sh600519").get("source"));
    }

    @Test
    void fallbackSourceGetsTheExplicitFallbackLabel() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", null),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", aShareFields()));

        Map<String, Object> quote = service.quote("a_share", "sh600519");

        assertEquals("EastMoney (fallback)", quote.get("source"));
        assertEquals(1500.00, quote.get("price"), "备用源的数据要真的用上");
    }

    /**
     * 备用标签由调用方显式传入，**不得**由 {@code provider.displayName()} 拼。
     *
     * <p>这条用 Yahoo 的 displayName 做反证：真实里它是
     * "Yahoo Finance (GC=F 期货)"，拼出来的备用标签会是
     * "Yahoo Finance (GC=F 期货) (fallback)"，而前端预期的是 "Yahoo Finance (fallback)"。</p>
     */
    @Test
    void fallbackLabelIsExplicitAndNotDerivedFromProviderDisplayName() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent (GC=F 期货)", null),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney (GC=F 期货)",
                        aShareFields()));

        assertEquals("EastMoney (fallback)", service.quote("a_share", "sh600519").get("source"));
    }

    // ==================== name 语义 ====================

    @Test
    void providerSuppliedNameOverridesTheInstrumentName() {
        Map<String, Object> fields = aShareFields();
        fields.put("name", "贵州茅台(改名后)");

        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", fields));

        assertEquals("贵州茅台(改名后)", service.quote("a_share", "sh600519").get("name"));
    }

    /**
     * Provider 不产出 {@code name}（上游字段空白）时，回落到标的登记名，
     * 而不是编一个出来——与原实现的条件覆盖一致。
     */
    @Test
    void omittedProviderNameFallsBackToTheInstrumentName() {
        Map<String, Object> fields = aShareFields();
        fields.remove("name");

        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", fields));

        assertEquals("贵州茅台", service.quote("a_share", "sh600519").get("name"));
    }

    // ==================== 熔断键与降级顺序 ====================

    @Test
    void usesTheExtendedVendorCircuitBreakerKeysAndRecordsBothOutcomes() {
        MarketSourceCircuitBreaker breaker = org.mockito.Mockito.mock(MarketSourceCircuitBreaker.class);
        org.mockito.Mockito.when(breaker.allowRequest(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        ExtendedMarketDataService service = serviceWithBreaker(breaker,
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", null),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", aShareFields()));

        service.quote("a_share", "sh600519");

        org.mockito.Mockito.verify(breaker)
                .recordFailure("extended.tencent.stock");
        org.mockito.Mockito.verify(breaker)
                .recordSuccess("extended.eastmoney.stock");
        org.mockito.Mockito.verify(breaker)
                .allowRequest("extended.eastmoney.stock");
    }

    @Test
    void chainOrderComesFromPriorityNotFromDeclarationOrder() {
        // 故意把备用源放在前面声明；注册表应按 priority 升序排成 腾讯 → 东方财富。
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", aShareFields()),
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", aShareFields()));

        Map<String, Object> quote = service.quote("a_share", "sh600519");

        assertEquals("Tencent", quote.get("source"), "主源标签应来自 priority 最小的腾讯");
    }

    /**
     * 从 {@code ExtendedMarketDataServiceTest} 迁来的测试，断言逐条保留。
     *
     * <p>它原本靠伪造 HTTP 响应来驱动内联实现；内联实现删除后，同一个行为现在住在
     * 服务层的注册表降级里，所以改用 stub Provider + **真实**熔断器来表达——
     * 熔断器的开合逻辑仍然是真的在跑，不是被打桩的。</p>
     *
     * <p>两次报价故意用不同标的（sh600519 / sh600520），避开服务层 800ms 的短缓存，
     * 否则第二次会直接命中缓存、根本走不到降级逻辑。</p>
     */
    @Test
    void opensThePrimaryCircuitAndStopsCallingItOnTheNextRequest() {
        AtomicInteger tencentCalls = new AtomicInteger();
        MarketSourceCircuitBreaker breaker = new MarketSourceCircuitBreaker(
                new SimpleMeterRegistry(), 1, Duration.ofHours(1), Clock.systemUTC());
        ExtendedMarketDataService service = serviceWithBreaker(breaker,
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", null, tencentCalls),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", aShareFields()));

        Map<String, Object> first = service.quote("a_share", "sh600519");
        Map<String, Object> second = service.quote("a_share", "sh600520");

        assertEquals("EastMoney (fallback)", first.get("source"));
        assertEquals("EastMoney (fallback)", second.get("source"));
        assertEquals(1, tencentCalls.get(), "Tencent 熔断后不应再次被调用");
        assertEquals("OPEN", breaker.state("extended.tencent.stock"));
    }

    // ==================== 失败语义 ====================

    @Test
    void everySourceFailingYieldsBadGatewayRatherThanAEmptyQuote() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", null),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", null));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.quote("a_share", "sh600519"));

        assertEquals(502, error.getStatusCode().value());
    }

    /**
     * 拿不到降级链时必须**响亮地失败**。
     *
     * <p>内联实现已经删掉了，所以"没有注册表"没有第二条路可走。
     * 这里钉的是：宁可 502，也不要悄悄回落到什么半份实现。</p>
     */
    @Test
    void missingRegistryFailsLoudlyInsteadOfSilentlyDegrading() {
        ExtendedMarketDataService noRegistry = new ExtendedMarketDataService(new ObjectMapper());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> noRegistry.quote("a_share", "sh600519"));

        assertEquals(502, error.getStatusCode().value());
        assertTrue(String.valueOf(error.getReason()).contains("无可用行情源"),
                "错误信息应指明没有行情源，实际: " + error.getReason());
    }

    // ==================== 重复实现不许回来 ====================

    /**
     * 结构性钉子：三个内联方法必须真的删掉。
     *
     * <p>这一步的意义就是消灭「同一份解析存在两处」。留着死代码，
     * 下一个人很容易把它当成"还没迁移的实现"再改一次，两边就此分叉。</p>
     */
    @Test
    void theInlineAShareImplementationsAreGoneNotJustUnused() {
        Set<String> methods = Arrays.stream(ExtendedMarketDataService.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertFalse(methods.contains("quoteTencent"), "内联腾讯 A股解析应为已删除");
        assertFalse(methods.contains("quoteEastmoney"), "内联东方财富报价应为已删除");
        assertFalse(methods.contains("quoteAShareWithFallback"), "内联降级逻辑应为已删除");
        assertTrue(methods.contains("registryQuote"), "取而代之的是注册表驱动的取数");
    }

    /**
     * {@code resolveInstrument} 与 {@code quote} 走同一条注册表路径，但返回的东西不同：
     * 它只用报价结果里的 {@code name}（顺便解析出标的真名），然后返回
     * {@link Map} 形式的标的视图，**不含价格**。
     *
     * <p>注意 {@code source} 是标的**登记**的来源（Tencent），不是实际供数的那个源——
     * 即使数据来自备用源也一样。这是切换前就有的行为，本次没有改它，
     * 所以这里按现状钉住，而不是按"应该的样子"。</p>
     */
    @Test
    void resolveInstrumentReturnsTheInstrumentViewAndUsesTheQuoteOnlyForTheName() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", null),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", aShareFields()));

        Map<String, Object> resolved = service.resolveInstrument("a_share", "sh600519");

        assertEquals(new TreeSet<>(Set.of("market", "symbol", "name", "currency", "source")),
                new TreeSet<>(resolved.keySet()));
        assertEquals("sh600519", resolved.get("symbol"));
        assertEquals("贵州茅台", resolved.get("name"), "名称取自实际供数的那次报价");
        assertEquals("CNY", resolved.get("currency"));
        assertEquals("Tencent", resolved.get("source"),
                "登记的来源，不是备用源的标签");
    }

    /**
     * 行情源全挂时，{@code resolveInstrument} 仍然返回标准化代码——
     * 它的职责是"把用户输入解析成标的"，不该因为上游抖动而失败。
     */
    @Test
    void resolveInstrumentStillResolvesTheSymbolWhenEverySourceIsDown() {
        ExtendedMarketDataService service = serviceWith(
                new StubProvider("Tencent", "extended.tencent.stock", 10, "Tencent", null),
                new StubProvider("EastMoney", "extended.eastmoney.stock", 30, "EastMoney", null));

        Map<String, Object> resolved = service.resolveInstrument("a_share", "sh600519");

        assertEquals("sh600519", resolved.get("symbol"));
        assertEquals("贵州茅台", resolved.get("name"), "上游不可用时回落到登记名");
    }

    // ==================== 夹具 ====================

    /** {@code TencentMarketDataProvider#parseAShare} 对一个完整载荷的产出。 */
    private static Map<String, Object> aShareFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("symbol", "sh600519");
        fields.put("name", "贵州茅台");
        fields.put("price", 1500.00);
        fields.put("prev_close", 1490.00);
        fields.put("open", 1495.00);
        fields.put("change", 10.00);
        fields.put("change_pct", 0.67);
        fields.put("high", 1510.00);
        fields.put("low", 1488.00);
        return fields;
    }

    private static ExtendedMarketDataService serviceWith(MarketDataProvider... providers) {
        return new ExtendedMarketDataService(new ObjectMapper(), null,
                new ProviderRegistry(List.of(providers)),
                WebClient.builder().build());
    }

    private static ExtendedMarketDataService serviceWithBreaker(MarketSourceCircuitBreaker breaker,
                                                                MarketDataProvider... providers) {
        return new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(providers)),
                WebClient.builder().build());
    }

    /** {@code payload == null} 表示这个源失败。 */
    private static final class StubProvider implements MarketDataProvider {

        private final String name;
        private final String sourceKey;
        private final int priority;
        private final String displayName;
        private final Map<String, Object> payload;
        private final AtomicInteger calls;

        StubProvider(String name, String sourceKey, int priority, String displayName,
                     Map<String, Object> payload) {
            this(name, sourceKey, priority, displayName, payload, null);
        }

        StubProvider(String name, String sourceKey, int priority, String displayName,
                     Map<String, Object> payload, AtomicInteger calls) {
            this.name = name;
            this.sourceKey = sourceKey;
            this.priority = priority;
            this.displayName = displayName;
            this.payload = payload;
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
            if (calls != null) {
                calls.incrementAndGet();
            }
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
        public String displayName() {
            return displayName;
        }

        @Override
        public String sourceKey(String market) {
            return sourceKey;
        }
    }
}