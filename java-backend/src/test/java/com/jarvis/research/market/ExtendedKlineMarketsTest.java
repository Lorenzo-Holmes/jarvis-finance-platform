package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import com.jarvis.research.market.provider.MarketDataProvider;
import com.jarvis.research.market.provider.ProviderRegistry;
import com.jarvis.research.market.provider.YahooMarketDataProvider;

import java.util.ArrayList;
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
 * 美股与加密货币K线改由 {@link ProviderRegistry} 驱动后的契约测试。
 *
 * <p>这里最要紧的一条是**行为保留**而非新功能：切换前美股日K是唯一不走熔断的K线路径，
 * 而它的熔断键 {@code extended.yahoo.stock} 与美股**报价**共用。如果迁移时"顺手统一"
 * 让它参与熔断，一次K线故障就会把实时行情一起切断，而两者只是同一接口的不同参数。
 * {@code usStockKlineNeverTouchesTheCircuitBreaker} 就是钉住这一点的。</p>
 */
class ExtendedKlineMarketsTest {

    // ==================== 美股 ====================

    @Test
    void usStockKlineGoesThroughTheRegistry() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20, rows(3)).unguarded());

        Map<String, Object> envelope = service.kline("us_stock", "AAPL", "1d", 10);

        assertEquals("us_stock", envelope.get("market"));
        assertEquals("AAPL", envelope.get("symbol"));
        assertEquals("1d", envelope.get("interval"));
        assertEquals(3, envelope.get("count"));
        assertEquals(3, rowsOf(envelope).size());
    }

    /**
     * **行为保留的钉子**：美股K线不得触碰熔断器。
     *
     * <p>切换前 {@code klineYahoo} 完全没有熔断包装。若迁移后统一走熔断，
     * K线的失败会打开 {@code extended.yahoo.stock}——那是美股报价用的键，
     * 于是K线故障会连带切断实时行情。</p>
     */
    @Test
    void usStockKlineNeverTouchesTheCircuitBreaker() {
        MarketSourceCircuitBreaker breaker = Mockito.mock(MarketSourceCircuitBreaker.class);
        ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(
                        klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20, rows(2)).unguarded())),
                WebClient.builder().build());

        assertEquals(2, rowsOf(service.kline("us_stock", "AAPL", "1d", 10)).size());

        Mockito.verify(breaker, Mockito.never()).allowRequest(ArgumentMatchers.anyString());
        Mockito.verify(breaker, Mockito.never()).recordSuccess(ArgumentMatchers.anyString());
        Mockito.verify(breaker, Mockito.never()).recordFailure(ArgumentMatchers.anyString());
    }

    @Test
    void usStockKlineEnvelopeKeepsTheSameKeys() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20, rows(3)).unguarded());

        Map<String, Object> envelope = service.kline("us_stock", "AAPL", "1d", 10);

        assertEquals(new TreeSet<>(Set.of(
                        "market", "symbol", "interval", "count", "data",
                        "analysis", "stale", "source", "range")),
                new TreeSet<>(envelope.keySet()));
        // 实时数据的行里没有 source，所以信封的 source 是 null——切换前后一致。
        assertEquals(null, envelope.get("source"));
        assertEquals(false, envelope.get("stale"));
    }

    /**
     * 反面：同一个市场、同一个标的，只要 Provider 声明参与熔断，服务层就**必须**记熔断。
     *
     * <p>有了这条，上面那条"不触碰熔断器"才说明是 {@code klineUsesCircuitBreaker} 起了作用，
     * 而不是恰好在某条分支上绕过去了。</p>
     */
    @Test
    void guardedKlineDoesTouchTheCircuitBreaker() {
        MarketSourceCircuitBreaker breaker = Mockito.mock(MarketSourceCircuitBreaker.class);
        Mockito.when(breaker.allowRequest(ArgumentMatchers.anyString())).thenReturn(true);
        ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(
                        klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20, rows(2)))),
                WebClient.builder().build());

        assertEquals(2, rowsOf(service.kline("us_stock", "AAPL", "1d", 10)).size());

        Mockito.verify(breaker).allowRequest("extended.yahoo.stock");
        Mockito.verify(breaker).recordSuccess("extended.yahoo.stock");
    }

    /**
     * 周期白名单是对外 API 契约：客户端传了个不存在的周期，该给 400 而不是 502。
     *
     * <p>切换前这个 400 来自 {@code yahooResult} 里的 {@code YAHOO_INTERVALS} 校验；
     * 迁移后校验留在服务层（Provider 对不支持的周期返回空列表，那是"取不到数"不是"参数错"）。</p>
     */
    @Test
    void usStockUnsupportedIntervalIsAClientErrorNotAGatewayError() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20, rows(3)).unguarded());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.kline("us_stock", "AAPL", "2h", 10));

        assertEquals(400, error.getStatusCode().value());
    }

    // ==================== 10 分钟聚合 ====================

    /**
     * 10m 是派生周期：服务层要 5m 数据自己聚合，而且取的条数是 3 倍（聚合会压缩根数）。
     */
    @Test
    void tenMinuteKlineIsAggregatedFromFiveMinuteRows() {
        RecordingProvider provider = klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20,
                fiveMinuteRows()).unguarded();
        ExtendedMarketDataService service = serviceWith(provider);

        Map<String, Object> envelope = service.kline("us_stock", "AAPL", "10m", 100);

        assertEquals("5m", provider.lastInterval, "10m 要向来源要 5m 数据");
        assertEquals(300, provider.lastLimit, "3 倍于请求条数，聚合后根数会压缩");
        assertEquals(2, rowsOf(envelope).size(), "4 根 5m 应聚合成 2 根 10m");
    }

    /** 聚合结果的第一根：开盘取首根、收盘取末根、最高最低取极值、成交量求和。 */
    @Test
    void aggregatedBucketTakesFirstOpenLastCloseAndExtremes() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20, fiveMinuteRows()).unguarded());

        List<Map<String, Object>> data = rowsOf(service.kline("us_stock", "AAPL", "10m", 100));
        Map<String, Object> first = data.get(0);

        assertEquals("2026-09-16T10:00:00Z", first.get("date"), "桶的时间戳取桶内第一根");
        assertEquals(100.0, first.get("open"));
        assertEquals(114.0, first.get("close"), "收盘取桶内末根");
        assertEquals(115.0, first.get("high"));
        assertEquals(95.0, first.get("low"));
        // 10 + 20。这条断言第一次跑出来是 40，暴露了一个**重构前就有**的 bug：
        // aggregateCandles 先把首根的成交量塞进新桶，之后那段无条件累加又把它加了一遍，
        // 于是每个桶的首根被算了两次。10 分钟K线的成交量因此一直偏高一根5分钟的量。
        assertEquals(30.0, first.get("volume"), "成交量求和，首根不能被算两次");
    }

    /**
     * 一次取多少原始K线受来源页大小限制：Yahoo 500、Binance 1000。
     * 用统一上限会让某一侧在 limit 较大时多出一段本没有的历史。
     */
    @Test
    void tenMinuteAggregationRespectsTheProviderPageLimit() {
        RecordingProvider yahoo = klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20,
                fiveMinuteRows(), 500).unguarded();
        RecordingProvider generous = klineStub("Yahoo", "us_stock", "extended.yahoo.stock", 20,
                fiveMinuteRows(), 1000).unguarded();

        serviceWith(yahoo).kline("us_stock", "AAPL", "10m", 400);
        serviceWith(generous).kline("us_stock", "AAPL", "10m", 400);

        assertEquals(500, yahoo.lastLimit, "limit*3=1200 超过页大小时按 500 取");
        assertEquals(1000, generous.lastLimit, "页大小是 Provider 的真实属性，取它自己的上限");
    }

    // ==================== 加密货币 ====================

    @Test
    void cryptoKlinePrimaryIsBinance() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Binance", "crypto", "extended.binance", 10, rows(2)),
                klineStub("Yahoo", "crypto", "extended.yahoo.crypto", 20, rows(5)));

        assertEquals(2, rowsOf(service.kline("crypto", "BTCUSDT", "1d", 10)).size(),
                "主源有数据就用主源");
    }

    /**
     * 空列表等同于失败——否则"K线为空"会当成功返回，降级永远不会发生。
     */
    @Test
    void emptyKlineResultCountsAsFailureAndTriggersFallback() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Binance", "crypto", "extended.binance", 10, List.of()),
                klineStub("Yahoo", "crypto", "extended.yahoo.crypto", 20, rows(5)));

        assertEquals(5, rowsOf(service.kline("crypto", "BTCUSDT", "1d", 10)).size(),
                "主源返回空列表必须降级");
    }

    @Test
    void cryptoKlineRecordsBothCircuitKeys() {
        MarketSourceCircuitBreaker breaker = Mockito.mock(MarketSourceCircuitBreaker.class);
        Mockito.when(breaker.allowRequest(ArgumentMatchers.anyString())).thenReturn(true);
        ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper(), breaker,
                new ProviderRegistry(List.of(
                        klineStub("Binance", "crypto", "extended.binance", 10, List.of()),
                        klineStub("Yahoo", "crypto", "extended.yahoo.crypto", 20, rows(2)))),
                WebClient.builder().build());

        service.kline("crypto", "BTCUSDT", "1d", 10);

        Mockito.verify(breaker).recordFailure("extended.binance");
        Mockito.verify(breaker).recordSuccess("extended.yahoo.crypto");
        Mockito.verify(breaker, Mockito.never()).recordSuccess("core.yahoo.crypto");
    }

    @Test
    void cryptoKlineFailureOfEverySourceYieldsBadGateway() {
        ExtendedMarketDataService service = serviceWith(
                klineStub("Binance", "crypto", "extended.binance", 10, List.of()),
                klineStub("Yahoo", "crypto", "extended.yahoo.crypto", 20, List.of()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.kline("crypto", "BTCUSDT", "1d", 10));

        assertEquals(502, error.getStatusCode().value());
    }

    // ==================== Yahoo Provider 的无网络路径 ====================

    /**
     * Provider 自己建 WebClient，所以真正的 HTTP 解析在这里测不了。
     * 能测的是**不联网就该被拒掉**的几种入参——它们在发请求之前就返回了。
     */
    @Test
    void yahooKlineRejectsUnsupportedInputsWithoutNetwork() {
        YahooMarketDataProvider yahoo = new YahooMarketDataProvider();

        assertEquals(List.of(), yahoo.kline("london_gold", "hf_XAU", "1d", 10),
                "黄金不做K线（core 伦敦金走新浪）");
        assertEquals(List.of(), yahoo.kline("crypto", "ETH", "1d", 10),
                "映射不成 Yahoo 交易对");
        assertEquals(List.of(), yahoo.kline("us_stock", "AAPL", "2h", 10),
                "来源不支持的周期");
        assertEquals(List.of(), yahoo.kline("us_stock", "AAPL", "1d", 0),
                "条数非正");
    }

    // klineRange 的映射断言在 MarketDataProvidersTest 里——它是包级测试钩子，同包断言。

    // ==================== 夹具 ====================

    /** 会记录最后一次调用参数的 Provider。 */
    private static final class RecordingProvider implements MarketDataProvider {
        private final String name;
        private final String market;
        private final String sourceKey;
        private final int priority;
        private final List<Map<String, Object>> payload;
        private final int maxKlineLimit;

        String lastInterval;
        int lastLimit = -1;

        /**
         * 是否参与熔断。默认 true（接口默认值）。
         *
         * <p>美股桩必须显式 {@link #unguarded()}，因为真实的 Yahoo Provider
         * 就是这么声明的——桩声明得和真实实现不一致，测出来的就不是生产行为。
         * 这一点第一版桩就踩了：它拿了默认的 true，于是服务层合理地认为
         * {@code extended.yahoo.stock} 处于熔断中，测试报了个假的 502。</p>
         */
        private boolean klineGuarded = true;

        RecordingProvider unguarded() {
            this.klineGuarded = false;
            return this;
        }

        RecordingProvider(String name, String market, String sourceKey, int priority,
                          List<Map<String, Object>> payload, int maxKlineLimit) {
            this.name = name;
            this.market = market;
            this.sourceKey = sourceKey;
            this.priority = priority;
            this.payload = payload;
            this.maxKlineLimit = maxKlineLimit;
        }

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
            return Map.of("error", "本测试不涉及报价");
        }

        @Override
        public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
            lastInterval = interval;
            lastLimit = limit;
            return payload;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String sourceKey(String candidate) {
            return sourceKey;
        }

        @Override
        public int maxKlineLimit() {
            return maxKlineLimit;
        }

        @Override
        public boolean klineUsesCircuitBreaker(String market) {
            return klineGuarded;
        }
    }

    private static RecordingProvider klineStub(String name, String market, String sourceKey,
                                               int priority, List<Map<String, Object>> payload) {
        return klineStub(name, market, sourceKey, priority, payload, 1000);
    }

    private static RecordingProvider klineStub(String name, String market, String sourceKey,
                                               int priority, List<Map<String, Object>> payload,
                                               int maxKlineLimit) {
        return new RecordingProvider(name, market, sourceKey, priority, payload, maxKlineLimit);
    }

    private static ExtendedMarketDataService serviceWith(MarketDataProvider... providers) {
        return new ExtendedMarketDataService(new ObjectMapper(), null,
                new ProviderRegistry(List.of(providers)),
                WebClient.builder().build());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) envelope.get("data");
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row("2026-09-16T10:0" + i + ":00Z", 100.0 + i, 101.0 + i, 99.0 + i, 10.0));
        }
        return rows;
    }

    /** 同一天 10:00 / 10:05 / 10:10 / 10:15，4 根 5 分钟应聚合成 2 根 10 分钟。 */
    private static List<Map<String, Object>> fiveMinuteRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-09-16T10:00:00Z", 100.0, 110.0, 95.0, 10.0));
        rows.add(row("2026-09-16T10:05:00Z", 110.0, 115.0, 96.0, 20.0));
        rows.add(row("2026-09-16T10:10:00Z", 111.0, 112.0, 90.0, 30.0));
        rows.add(row("2026-09-16T10:15:00Z", 105.0, 120.0, 100.0, 40.0));
        return rows;
    }

    private static Map<String, Object> row(String date, double open, double high,
                                           double low, double volume) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("open", open);
        row.put("close", high - 1);
        row.put("high", high);
        row.put("low", low);
        row.put("volume", volume);
        return row;
    }
}