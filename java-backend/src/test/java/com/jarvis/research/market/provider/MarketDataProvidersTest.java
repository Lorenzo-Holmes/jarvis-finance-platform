package com.jarvis.research.market.provider;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.config.JarvisProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider 契约测试。
 *
 * <p>只验证不需要网络的纯契约行为（name / supports / priority / sourceKey / 能力开关）
 * 以及「不触网就必然失败」的分支（新浪不提供实时行情、Binance 不支持的周期）。
 * 不启动 Spring 容器，不使用 mock 网络。</p>
 */
class MarketDataProvidersTest {

    private final YahooMarketDataProvider yahoo = new YahooMarketDataProvider();
    private final EastMoneyMarketDataProvider eastMoney = new EastMoneyMarketDataProvider();
    private final BinanceMarketDataProvider binance = new BinanceMarketDataProvider();
    private final SinaMarketDataProvider sina = new SinaMarketDataProvider();
    private final TencentMarketDataProvider tencent =
            new TencentMarketDataProvider(new JarvisProperties(), new ObjectMapper());

    @Test
    void allProvidersAreSpringComponents() {
        assertNotNull(YahooMarketDataProvider.class.getAnnotation(Component.class));
        assertNotNull(EastMoneyMarketDataProvider.class.getAnnotation(Component.class));
        assertNotNull(BinanceMarketDataProvider.class.getAnnotation(Component.class));
        assertNotNull(SinaMarketDataProvider.class.getAnnotation(Component.class));
    }

    /**
     * 通用不变量：**声明支持某市场的 provider，必须至少在该市场能做一件事**。
     *
     * <p>注意 {@code supportsQuote}/{@code supportsKline} 是默认 {@code true} 的
     * **opt-out 能力开关**（为「只提供K线」的来源如新浪而设），本身不代表市场归属；
     * 市场归属只看 {@code supports(market)}。注册表也是先按 {@code supports} 取链、
     * 再用这两个开关过滤。所以有意义的检查方向是 supports ⇒ 至少一项能力，
     * 而不是 supportsQuote ⇒ supports。</p>
     *
     * <p>我最初把这条写反了，测试立刻报出「Yahoo 声明能报 gold_etf 却不支持该市场」——
     * 报错是对的、断言是错的，这也正好说明这类不变量值得先写下来再验证。</p>
     */
    @Test
    void everySupportedMarketHasAtLeastOneCapability() {
        List<String> markets = List.of("gold_etf", "london_gold", "a_share", "us_stock", "crypto");

        for (MarketDataProvider provider : List.of(yahoo, eastMoney, binance, sina, tencent)) {
            for (String market : markets) {
                if (!provider.supports(market)) {
                    continue;
                }
                assertTrue(provider.supportsQuote(market) || provider.supportsKline(market),
                        provider.name() + " 声明支持 " + market
                                + "，却既不报价也不取K线——进链只会空转");
            }
        }
    }

    // ==================== Yahoo ====================

    @Test
    void yahooContract() {
        assertEquals("Yahoo", yahoo.name());
        assertEquals(20, yahoo.priority());
        assertEquals("Yahoo Finance (GC=F 期货)", yahoo.displayName());

        assertTrue(yahoo.supports("london_gold"));
        assertTrue(yahoo.supports("LONDON_GOLD"));
        assertFalse(yahoo.supports("gold_etf"));
        assertFalse(yahoo.supports("a_share"));
        assertFalse(yahoo.supports("crypto"));
        assertFalse(yahoo.supports(null));
        // us_stock 的报价逻辑尚未迁移：quote() 对权益类标的必然返回 error，
        // 所以不能声明支持它（见 yahooNeverDeclaresMarketItCannotQuote）。
        assertFalse(yahoo.supports("us_stock"));

        // sourceKey 与能力声明是两件事：us_stock 的键是扩展行情服务既有的熔断键，保持不变。
        assertEquals("core.yahoo.gold-futures", yahoo.sourceKey("london_gold"));
        assertEquals("extended.yahoo.stock", yahoo.sourceKey("us_stock"));
        assertEquals("core.yahoo.crypto", yahoo.sourceKey("crypto"));

        assertTrue(yahoo.supportsQuote("london_gold"));
        assertFalse(yahoo.supportsKline("london_gold"));
        assertFalse(yahoo.supportsKline("us_stock"));

        assertEquals(List.of(), yahoo.kline("GC=F", "1d", 10));
    }

    @Test
    void yahooRejectsUnmigratedSymbolWithoutNetwork() {
        Map<String, Object> quote = yahoo.quote("AAPL");
        assertTrue(quote.containsKey("error"), "非黄金标的应显式返回 error 而不是错标的的数据");
    }

    /**
     * 不变量：**声明支持的市场，其真实调用 symbol 必须能过标的闸门**。
     *
     * <p>这条不变量曾被违反：{@code supports("us_stock")} 返回 true，
     * 而 {@code quote("AAPL")} 必然失败。代价不是"取不到数"这么轻——注册表会把必然失败的
     * provider 选进 us_stock 的链里，调用方据此以为该市场可服务，熔断器还会为
     * {@code extended.yahoo.stock} 记下一次永远不该发生的失败。</p>
     *
     * <p>把闸门与声明放在同一条测试里断言，两者就无法再悄悄分叉。</p>
     */
    @Test
    void yahooNeverDeclaresMarketItCannotQuote() {
        assertTrue(yahoo.acceptsQuoteSymbol("hf_XAU"), "core london_gold 链用 hf_XAU 调用");
        assertTrue(yahoo.acceptsQuoteSymbol("GC=F"), "Yahoo 侧的黄金合约代码");
        assertTrue(yahoo.acceptsQuoteSymbol(null), "symbol 缺失时仍走原实现路径");
        assertFalse(yahoo.acceptsQuoteSymbol("AAPL"), "权益类标的被闸门拒绝");

        assertFalse(yahoo.supports("us_stock"),
                "既然 quote(AAPL) 必然失败，supports(us_stock) 就不能为 true");
        assertTrue(yahoo.supports("london_gold"), "而 london_gold 的 symbol 确实能过闸门");
    }

    // ==================== EastMoney ====================

    @Test
    void eastMoneyContract() {
        assertEquals("EastMoney", eastMoney.name());
        assertEquals(30, eastMoney.priority());
        assertEquals("EastMoney", eastMoney.displayName());

        assertTrue(eastMoney.supports("gold_etf"));
        assertTrue(eastMoney.supports("a_share"));
        assertTrue(eastMoney.supports("A_SHARE"));
        assertFalse(eastMoney.supports("london_gold"));
        assertFalse(eastMoney.supports("us_stock"));
        assertFalse(eastMoney.supports("crypto"));
        assertFalse(eastMoney.supports(null));

        assertEquals("core.eastmoney.etf", eastMoney.sourceKey("gold_etf"));
        assertEquals("extended.eastmoney.stock", eastMoney.sourceKey("a_share"));
        assertEquals("core.eastmoney.crypto", eastMoney.sourceKey("crypto"));

        assertTrue(eastMoney.supportsQuote("a_share"));
        assertTrue(eastMoney.supportsKline("a_share"));
        // 黄金ETF 的K线仍只由腾讯承接，东方财富不掺和。
        assertFalse(eastMoney.supportsKline("gold_etf"));
        assertFalse(eastMoney.supportsKline("london_gold"));

        // K线的熔断键与实时行情分开：两者是不同主机上的独立来源。
        assertEquals("extended.eastmoney.kline", eastMoney.klineSourceKey("a_share"));
        assertEquals("core.eastmoney.etf", eastMoney.klineSourceKey("gold_etf"));

        // 空标的直接短路，不发请求。
        assertEquals(List.of(), eastMoney.kline("  ", "1d", 10));
    }

    @Test
    void eastMoneyBlankSymbolReturnsErrorWithoutNetwork() {
        Map<String, Object> quote = eastMoney.quote("  ");
        assertTrue(quote.containsKey("error"));
    }

    /**
     * 东方财富日K的响应是逗号分隔的字符串数组，顺序为 日期,开,收,高,低,量,...
     * 抽错一个下标不会报错，只会让 K 线图画错——所以逐槽钉住。
     */
    @Test
    void eastMoneyKlineParsesTheCommaStringSlotsAndSkipsMalformedRows() throws Exception {
        JsonNode raw = new ObjectMapper().readTree("""
                ["2026-09-15,1490.00,1500.00,1510.00,1485.00,1000",
                 "2026-09-16,1500.00,1510.00,1520.00,1490.00,12345",
                 "字段不足,会被跳过"]
                """);

        List<Map<String, Object>> rows = eastMoney.parseKlineRows(raw, 10);

        assertEquals(2, rows.size(), "字段不足的行应被跳过");
        Map<String, Object> last = rows.get(1);
        assertEquals("2026-09-16", last.get("date"));
        assertEquals(1500.00, last.get("open"), "下标 1 是开盘价");
        assertEquals(1510.00, last.get("close"), "下标 2 是收盘价");
        assertEquals(1520.00, last.get("high"), "下标 3 是最高价");
        assertEquals(1490.00, last.get("low"), "下标 4 是最低价");
        assertEquals(12345.0, last.get("volume"), "下标 5 是成交量");
        assertEquals(new java.util.TreeSet<>(java.util.Set.of(
                        "date", "open", "close", "high", "low", "volume")),
                new java.util.TreeSet<>(last.keySet()));
    }

    @Test
    void eastMoneyKlineKeepsOnlyTheLastBarsWhenLimitIsSmaller() throws Exception {
        JsonNode raw = new ObjectMapper().readTree("""
                ["2026-09-14,1,1,1,1,1",
                 "2026-09-15,2,2,2,2,2",
                 "2026-09-16,3,3,3,3,3"]
                """);

        List<Map<String, Object>> rows = eastMoney.parseKlineRows(raw, 2);

        assertEquals(2, rows.size());
        assertEquals("2026-09-15", rows.get(0).get("date"), "截断应保留最后 limit 条");
        assertEquals("2026-09-16", rows.get(1).get("date"));
    }

    // ==================== Binance ====================

    @Test
    void binanceContract() {
        assertEquals("Binance", binance.name());
        assertEquals(10, binance.priority());
        assertEquals("Binance", binance.displayName());

        assertTrue(binance.supports("crypto"));
        assertTrue(binance.supports("CRYPTO"));
        assertFalse(binance.supports("a_share"));
        assertFalse(binance.supports("us_stock"));
        assertFalse(binance.supports("london_gold"));
        assertFalse(binance.supports("gold_etf"));
        assertFalse(binance.supports(null));

        assertEquals("extended.binance", binance.sourceKey("crypto"));
        assertEquals("extended.binance", binance.sourceKey("us_stock"));

        assertTrue(binance.supportsQuote("crypto"));
        assertTrue(binance.supportsKline("crypto"));
    }

    @Test
    void binanceUnsupportedIntervalReturnsEmptyListWithoutNetwork() {
        assertEquals(List.of(), binance.kline("BTC", "3m", 10));
        assertEquals(List.of(), binance.kline("BTC", "2h", 10));
    }

    @Test
    void binanceBlankSymbolReturnsErrorWithoutNetwork() {
        Map<String, Object> quote = binance.quote(null);
        assertTrue(quote.containsKey("error"));
    }

    // ==================== Sina ====================

    @Test
    void sinaContract() {
        assertEquals("Sina", sina.name());
        assertEquals(20, sina.priority());
        assertEquals("Sina Finance", sina.displayName());

        assertTrue(sina.supports("london_gold"));
        assertTrue(sina.supports("London_Gold"));
        assertFalse(sina.supports("gold_etf"));
        assertFalse(sina.supports("a_share"));
        assertFalse(sina.supports("us_stock"));
        assertFalse(sina.supports("crypto"));
        assertFalse(sina.supports(null));

        assertEquals("core.sina.gold-kline", sina.sourceKey("london_gold"));
        assertEquals("core.sina.crypto", sina.sourceKey("crypto"));

        assertFalse(sina.supportsQuote("london_gold"));
        assertTrue(sina.supportsKline("london_gold"));
    }

    @Test
    void sinaQuoteAlwaysReturnsErrorMap() {
        Map<String, Object> quote = sina.quote("hf_XAU");
        assertTrue(quote.containsKey("error"));
        assertEquals("Sina 不提供实时行情", quote.get("error"));
    }
}