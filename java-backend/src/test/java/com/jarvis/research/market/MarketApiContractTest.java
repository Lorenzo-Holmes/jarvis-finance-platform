package com.jarvis.research.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.market.dto.QuoteDTO;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 市场接口的 **JSON 契约测试**。
 *
 * DTO 统一（Map&lt;String,Object&gt; → QuoteDTO/KlineDTO/MarketStatusDTO）必须对前端**完全透明**，
 * 而这类重构最危险的失败方式是：某个字段悄悄变成 null 又多出一个键、或者干脆少一个键，
 * 编译和既有单测都不会报错，只有前端页面默默显示错误。
 *
 * 因此这里把当前响应的**键集**钉死在 JSON 层面：先序列化成 JsonNode 再断言，
 * 这样无论被断言的对象今天是 Map 还是 DTO，测试都同样有效——
 * 迁移前后都必须通过，这正是它的价值。
 *
 * 断言的是**键集与取值**，不是字节序：JSON 消费者的契约里不含对象内键顺序。
 */
class MarketApiContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private MarketDataService newService(PriceSnapshotRepository snapshotRepo,
                                         KlineDailyRepository klineRepo) {
        return new MarketDataService(snapshotRepo, klineRepo);
    }

    // ==================== /api/market/prices ====================

    /** 内存缓存命中路径：Provider 报价 + 服务层补的元数据，键集必须完整。 */
    @Test
    void cachedQuoteExposesProviderFieldsPlusServiceMetadata() {
        MarketDataService service = newService(mock(PriceSnapshotRepository.class),
                mock(KlineDailyRepository.class));

        Map<String, Object> cached = new LinkedHashMap<>();
        cached.put("symbol", "sh518850");
        cached.put("name", "黄金ETF华夏");
        cached.put("price", 10.55);
        cached.put("prev_close", 10.40);
        cached.put("open", 10.42);
        cached.put("high", 10.60);
        cached.put("low", 10.38);
        cached.put("change", 0.15);
        cached.put("change_pct", 1.44);
        cached.put("source_quote_time", LocalDateTime.now().toString());
        cached.put("market", "gold_etf");
        cached.put("source", "Tencent");
        cached.put("quote_time", LocalDateTime.now().toString());
        cached.put("received_at", LocalDateTime.now().toString());
        cached.put("stale", false);
        cache(service).put("gold_etf", cached);

        ApiResponse<Object> response = ApiResponse.ok(service.getLatestPrices());
        JsonNode data = json(response).path("data");
        JsonNode quote = data.path("gold_etf");

        assertEquals(Set.of("code", "message", "data"), keysOf(json(response)));
        assertEquals(Set.of(
                        "symbol", "name", "price", "prev_close", "open", "high", "low",
                        "change", "change_pct", "source_quote_time",
                        "market", "source", "quote_time", "received_at", "stale"),
                keysOf(quote));
        assertEquals("gold_etf", quote.path("market").asText());
        assertEquals("Tencent", quote.path("source").asText());
        assertEquals(10.55, quote.path("price").asDouble());
        assertFalse(quote.path("stale").asBoolean());
    }

    /**
     * 无行情路径：既没有内存报价、库里也没有快照时，市场键应当**整体缺席**，
     * 而不是塞一个 {price: null} 让前端把它当成真实报价。
     *
     * 注意 getLatestPrices() 只读缓存与数据库，不会触发外部请求——
     * 抓取在 @Scheduled refreshLivePrices() 里，所以这个断言与当前时钟无关。
     */
    @Test
    void marketsWithoutAnyQuoteAreOmittedRatherThanFilledWithNulls() {
        MarketDataService service = newService(mock(PriceSnapshotRepository.class),
                mock(KlineDailyRepository.class));

        JsonNode data = json(ApiResponse.ok(service.getLatestPrices())).path("data");

        assertEquals(Set.of(), keysOf(data));
    }

    /** 数据库兜底路径：字段由快照实体拼装，键集与缓存路径**不同**（无 source/received_at）。 */
    @Test
    void databaseFallbackQuoteHasItsOwnKeySetWithoutProviderMetadata() {
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        MarketDataService service = newService(snapshotRepo, mock(KlineDailyRepository.class));
        when(snapshotRepo.findTopByMarketOrderByTsDesc("gold_etf")).thenReturn(java.util.Optional.of(
                new PriceSnapshot("gold_etf", 10.55, 0.15, 1.44, 10.40, 10.42, 10.60, 10.38,
                        LocalDateTime.now())));

        JsonNode quote = json(ApiResponse.ok(service.getLatestPrices())).path("data").path("gold_etf");

        assertEquals(Set.of(
                        "market", "symbol", "name", "price", "change", "change_pct",
                        "prev_close", "open", "high", "low", "quote_time", "stale"),
                keysOf(quote));
        assertEquals("sh518850", quote.path("symbol").asText());
        assertEquals("黄金ETF华夏", quote.path("name").asText());
    }

    // ==================== QuoteDTO 边界的保真性 ====================

    /**
     * 最强形式：把**内部 Map 表示**与**对外 DTO 表示**各自整体序列化后逐节点比对。
     *
     * <p>两者相等，就是 {@code getLatestPriceView()} 对 {@code getLatestPrices()} 忠实适配的证明；
     * 由于 {@code /api/market/prices} 与 {@code /api/market/prices/stream} 共用这一个转换点，
     * 这也同时保证两条前端通道的行情形状不会各自漂移。</p>
     */
    @Test
    void typeViewReproducesTheFullProviderQuoteNodeForNode() {
        MarketDataService service = newService(mock(PriceSnapshotRepository.class),
                mock(KlineDailyRepository.class));
        cache(service).put("gold_etf", fullProviderQuote());

        assertEquals(json(service.getLatestPrices()), json(service.getLatestPriceView()));
    }

    @Test
    void typeViewReproducesTheDatabaseFallbackQuoteNodeForNode() {
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        MarketDataService service = newService(snapshotRepo, mock(KlineDailyRepository.class));
        when(snapshotRepo.findTopByMarketOrderByTsDesc("gold_etf")).thenReturn(java.util.Optional.of(
                new PriceSnapshot("gold_etf", 10.55, 0.15, 1.44, 10.40, 10.42, 10.60, 10.38,
                        LocalDateTime.now())));

        assertEquals(json(service.getLatestPrices()), json(service.getLatestPriceView()));
    }

    /** 闭市占位对象：只有 status 与 stale 两个键，其余字段必须被 NON_NULL 收掉。 */
    @Test
    void typeViewReproducesTheMarketClosedMarkerNodeForNode() {
        MarketDataService service = newService(mock(PriceSnapshotRepository.class),
                mock(KlineDailyRepository.class));
        cache(service).put("gold_etf", new LinkedHashMap<>(Map.of("status", "market_closed")));

        Map<String, QuoteDTO> view = service.getLatestPriceView();
        JsonNode marker = json(view).path("gold_etf");

        assertEquals(json(service.getLatestPrices()), json(view));
        assertEquals(Set.of("status", "stale"), keysOf(marker));
        assertEquals("market_closed", marker.path("status").asText());
    }

    /**
     * 稀疏来源不能凭空长出 null 键。东方财富不发 {@code source_quote_time}，
     * Yahoo 不发 {@code name/open/high/low}——迁移前这些键就是缺席的，迁移后必须仍然缺席。
     */
    @Test
    void typeViewKeepsKeysThatSparseProvidersNeverSent() {
        MarketDataService service = newService(mock(PriceSnapshotRepository.class),
                mock(KlineDailyRepository.class));
        Map<String, Object> eastmoney = new LinkedHashMap<>();
        eastmoney.put("symbol", "sh600519");
        eastmoney.put("name", "贵州茅台");
        eastmoney.put("price", 1500.0);
        eastmoney.put("prev_close", 1490.0);
        eastmoney.put("change", 10.0);
        eastmoney.put("change_pct", 0.67);
        cache(service).put("gold_etf", eastmoney);

        JsonNode quote = json(service.getLatestPriceView()).path("gold_etf");

        assertEquals(json(service.getLatestPrices()), json(service.getLatestPriceView()));
        assertFalse(keysOf(quote).contains("source_quote_time"), "来源没发 source_quote_time，键就不该出现");
        assertFalse(keysOf(quote).contains("open"), "来源没发 open，键就不该出现（更不能是 null）");
    }

    /**
     * 唯一的**有意收窄**：行情源若返回未知键，DTO 会丢弃它。
     * 对外契约应当由 QuoteDTO 定义，而不是"行情源返回了什么就透传什么"。
     * 这条测试把该决定显式钉住，避免将来有人误以为是漏迁。
     */
    @Test
    void typeViewDropsUnknownProviderKeysByDesign() {
        MarketDataService service = newService(mock(PriceSnapshotRepository.class),
                mock(KlineDailyRepository.class));
        Map<String, Object> withExtra = fullProviderQuote();
        withExtra.put("vendor_private_field", "不应外泄");
        cache(service).put("gold_etf", withExtra);

        JsonNode quote = json(service.getLatestPriceView()).path("gold_etf");

        assertTrue(keysOf(json(service.getLatestPrices()).path("gold_etf")).contains("vendor_private_field"),
                "内部 Map 仍然带着这个键");
        assertFalse(keysOf(quote).contains("vendor_private_field"),
                "对外 DTO 只暴露契约内的字段");
    }

    /** 腾讯口径的完整报价：10 个来源字段 + 5 个服务层元数据字段。 */
    private static Map<String, Object> fullProviderQuote() {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("symbol", "sh518850");
        quote.put("name", "黄金ETF华夏");
        quote.put("price", 10.55);
        quote.put("prev_close", 10.40);
        quote.put("open", 10.42);
        quote.put("high", 10.60);
        quote.put("low", 10.38);
        quote.put("change", 0.15);
        quote.put("change_pct", 1.44);
        quote.put("source_quote_time", LocalDateTime.now().toString());
        quote.put("market", "gold_etf");
        quote.put("source", "Tencent");
        quote.put("quote_time", LocalDateTime.now().toString());
        quote.put("received_at", LocalDateTime.now().toString());
        quote.put("stale", false);
        return quote;
    }

    // ==================== /api/market/kline (day) ====================

    @Test
    void dailyKlineEnvelopeKeepsItsRangeAsOfAndBarKeys() {
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        KlineDailyRepository klineRepo = mock(KlineDailyRepository.class);
        MarketDataService service = newService(snapshotRepo, klineRepo);
        when(klineRepo.findByMarketOrderByDateDesc(eq("gold_etf"), any(Pageable.class)))
                .thenReturn(List.of(
                        new KlineDaily("gold_etf", "2026-09-04", 10.1, 10.4, 10.5, 10.0, 1200.0),
                        new KlineDaily("gold_etf", "2026-09-03", 9.8, 10.0, 10.2, 9.7, 1000.0)));

        JsonNode data = json(ApiResponse.ok(service.getDailyKline("gold_etf", 2))).path("data");

        assertEquals(Set.of("market", "range", "as_of", "count", "data"), keysOf(data));
        assertEquals(Set.of("min", "max", "count"), keysOf(data.path("range")));
        assertEquals(2, data.path("count").asInt());
        // 返回前已反转为时间升序：min 是最早一根，max/as_of 是最新一根
        assertEquals("2026-09-03", data.path("range").path("min").asText());
        assertEquals("2026-09-04", data.path("range").path("max").asText());
        assertEquals("2026-09-04", data.path("as_of").asText());
        assertEquals("2026-09-03", data.path("data").get(0).path("date").asText());
        assertEquals(Set.of("date", "open", "close", "high", "low", "volume"),
                keysOf(data.path("data").get(0)));
    }

    @Test
    void emptyDailyKlineStillReturnsTheEnvelopeRatherThanNull() {
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        KlineDailyRepository klineRepo = mock(KlineDailyRepository.class);
        MarketDataService service = newService(snapshotRepo, klineRepo);
        when(klineRepo.findByMarketOrderByDateDesc(eq("gold_etf"), any(Pageable.class)))
                .thenReturn(List.of());

        JsonNode data = json(ApiResponse.ok(service.getDailyKline("gold_etf", 2))).path("data");

        assertEquals(Set.of("market", "range", "as_of", "count", "data"), keysOf(data));
        assertEquals(0, data.path("count").asInt());
        assertTrue(data.path("range").path("min").isNull());
        assertTrue(data.path("data").isArray());
        assertEquals(0, data.path("data").size());
    }

    // ==================== /api/market/kline (minute) ====================

    @Test
    void minuteKlineEnvelopeUsesIntervalInsteadOfRange() {
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        KlineDailyRepository klineRepo = mock(KlineDailyRepository.class);
        MarketDataService service = newService(snapshotRepo, klineRepo);

        List<PriceSnapshot> snaps = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 9, 16, 10, 0);
        for (int i = 0; i < 6; i++) {
            snaps.add(new PriceSnapshot("gold_etf", 10.0 + i * 0.01, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0,
                    base.plusMinutes(i)));
        }
        // 服务内部会 reverse，故这里给降序，贴合仓储「Desc」语义
        List<PriceSnapshot> desc = new ArrayList<>(snaps);
        java.util.Collections.reverse(desc);
        when(snapshotRepo.findByMarketOrderByTsDesc(eq("gold_etf"), any(Pageable.class)))
                .thenReturn(desc);

        JsonNode data = json(ApiResponse.ok(service.getMinuteKline("gold_etf", 5, 120))).path("data");

        assertEquals(Set.of("market", "interval", "count", "data"), keysOf(data));
        assertEquals("gold_etf", data.path("market").asText());
        assertEquals("5m", data.path("interval").asText());
        assertEquals(Set.of("date", "open", "close", "high", "low", "volume"),
                keysOf(data.path("data").get(0)));
        // 分钟K的 date 是 "yyyy-MM-dd HH:mm" 桶键，不是 ISO 串
        assertTrue(data.path("data").get(0).path("date").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "分钟K的 date 必须是桶键格式，实际: " + data.path("data").get(0).path("date").asText());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> cache(MarketDataService service) {
        return (Map<String, Map<String, Object>>) ReflectionTestUtils.getField(service, "livePriceCache");
    }

    // ==================== /api/market/session ====================

    /** 市场状态信封的键集；crypto 恒为开市，可确定性地断言取值。 */
    @Test
    void sessionEnvelopeKeepsItsMarketStatusKeys() {
        ExtendedMarketDataService extended = new ExtendedMarketDataService(new ObjectMapper());

        JsonNode data = json(ApiResponse.ok(extended.session("crypto"))).path("data");

        assertEquals(Set.of("market", "is_open", "status", "label", "timezone", "checked_at", "disclaimer"),
                keysOf(data));
        assertEquals("crypto", data.path("market").asText());
        assertTrue(data.path("is_open").asBoolean());
        assertEquals("open", data.path("status").asText());
        assertFalse(data.path("label").asText().isBlank());
        assertFalse(data.path("timezone").asText().isBlank());
        assertFalse(data.path("checked_at").asText().isBlank());
        assertFalse(data.path("disclaimer").asText().isBlank());
    }

    /**
     * a_share / us_stock 的开闭取决于当前时钟，无法确定性断言取值——
     * 但**键集必须与开闭无关**：闭市也要给全这 7 个键，前端才不会拿到 undefined。
     */
    @Test
    void sessionKeySetDoesNotDependOnWhetherTheMarketIsOpen() {
        ExtendedMarketDataService extended = new ExtendedMarketDataService(new ObjectMapper());

        for (String market : List.of("a_share", "us_stock")) {
            JsonNode data = json(ApiResponse.ok(extended.session(market))).path("data");
            assertEquals(Set.of("market", "is_open", "status", "label", "timezone", "checked_at", "disclaimer"),
                    keysOf(data), market + " 的键集不应随开闭变化");
            assertEquals(data.path("is_open").asBoolean(), "open".equals(data.path("status").asText()),
                    market + " 的 is_open 与 status 必须自洽");
        }
    }
}