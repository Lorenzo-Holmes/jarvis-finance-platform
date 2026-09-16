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
        assertFalse(yahoo.supports(null));
        // 美股与加密货币的报价本已实现，所以可以声明支持。
        assertTrue(yahoo.supports("us_stock"));
        assertTrue(yahoo.supports("crypto"));

        assertEquals("core.yahoo.gold-futures", yahoo.sourceKey("london_gold"));
        assertEquals("extended.yahoo.stock", yahoo.sourceKey("us_stock"));
        // 显式写死：接口默认推导会给出 core.yahoo.crypto，
        // 而加密货币备用源的既有运维键是 extended.yahoo.crypto。
        assertEquals("extended.yahoo.crypto", yahoo.sourceKey("crypto"));

        assertTrue(yahoo.supportsQuote("london_gold"));
        assertFalse(yahoo.supportsKline("london_gold"));
        // K线仍未迁移：声明支持报价不等于能做K线。
        assertFalse(yahoo.supportsKline("us_stock"));
        assertFalse(yahoo.supportsKline("crypto"));

        assertEquals(List.of(), yahoo.kline("GC=F", "1d", 10));
    }

    @Test
    void yahooRejectsUnmappableCryptoSymbolWithoutNetwork() {
        // ETH 无法映射成 Yahoo 交易对（只有 xxxUSDT 形式才认），应显式报错且不发请求。
        Map<String, Object> quote = yahoo.quote("crypto", "ETH");
        assertTrue(quote.containsKey("error"), "映射不了就报错，而不是拿错标的去请求");
    }

    @Test
    void yahooRejectsBlankStockSymbolWithoutNetwork() {
        Map<String, Object> quote = yahoo.quote("us_stock", "   ");
        assertTrue(quote.containsKey("error"));
    }

    /**
     * 标的映射：美股把 {@code .} 换成 {@code -}，加密货币由 {@code xxxUSDT} 换成 {@code xxx-USD}。
     * 这两条是扩展行情服务既有的映射规则，搬进 Provider 时不能走样。
     */
    @Test
    void yahooMapsSymbolsTheWayTheExtendedServiceDid() {
        assertEquals("BRK-B", YahooMarketDataProvider.stockSymbol("BRK.B"));
        assertEquals("AAPL", YahooMarketDataProvider.stockSymbol("AAPL"));
        assertEquals("BTC-USD", YahooMarketDataProvider.cryptoSymbol("BTCUSDT"));
        assertEquals("SOL-USD", YahooMarketDataProvider.cryptoSymbol("SOLUSDT"));
    }

    /**
     * 单参调用时的市场推断。{@link MarketDataProvider#quote(String)} 属冻结契约，
     * 不区分市场的调用方仍按标的调用，所以要能推断对。
     */
    @Test
    void yahooInfersMarketFromSymbolOnlyAsAFallback() {
        assertEquals("london_gold", YahooMarketDataProvider.marketOf("hf_XAU"));
        assertEquals("london_gold", YahooMarketDataProvider.marketOf("GC=F"));
        assertEquals("london_gold", YahooMarketDataProvider.marketOf(null));
        assertEquals("crypto", YahooMarketDataProvider.marketOf("BTCUSDT"));
        assertEquals("us_stock", YahooMarketDataProvider.marketOf("AAPL"));
        assertEquals("us_stock", YahooMarketDataProvider.marketOf("BRK.B"));
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