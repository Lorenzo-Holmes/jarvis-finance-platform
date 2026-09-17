package com.jarvis.research.market.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.config.JarvisProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider 的 HTTP 契约测试：**请求参数**与**报文解析**一起钉住。
 *
 * <p>这是本次重构留下的最大空洞的补丁。所有取数与解析都搬进了 Provider，
 * 而 Provider 自己建 WebClient，于是此前没有任何测试覆盖真实的 URL 与真实报文——
 * 只有"桩 Provider + 服务层信封"的测试。那类测试证明不了 {@code klt} 是 5 还是 101、
 * {@code secid} 前缀是 {@code 1.} 还是 {@code 0.}、{@code range} 是 1y 还是 5d：
 * 这些参数传错了都不会报错，只会静默换一种数据回来。</p>
 */
class ProviderHttpContractTest {

    // ==================== EastMoney：klt 是日K与分钟K之间唯一的差别 ====================

    @Test
    void eastMoneySendsTheKltThatMatchesTheRequestedInterval() {
        StubWebClient stub = StubWebClient.serving(klines("\"2026-09-16,10,11,12,9,100\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        provider.kline("sh600519", "1d", 100);
        assertTrue(stub.lastUrl().contains("klt=101"), "日线必须是 101，实际: " + stub.lastUrl());

        provider.kline("sh600519", "5m", 100);
        assertTrue(stub.lastUrl().contains("klt=5"), "5 分钟就是 5，实际: " + stub.lastUrl());

        provider.kline("sh600519", "1h", 100);
        assertTrue(stub.lastUrl().contains("klt=60"), "1h 换算成 60，实际: " + stub.lastUrl());
    }

    @Test
    void eastMoneyBuildsSecIdByExchangePrefix() {
        StubWebClient stub = StubWebClient.serving(klines("\"2026-09-16,10,11,12,9,100\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        provider.kline("sh600519", "1d", 100);
        assertTrue(stub.lastUrl().contains("secid=1.600519"), "沪市前缀 1.，实际: " + stub.lastUrl());

        provider.kline("sz000001", "1d", 100);
        assertTrue(stub.lastUrl().contains("secid=0.000001"), "深市前缀 0.，实际: " + stub.lastUrl());
    }

    @Test
    void eastMoneyCapsTheLimitAtTheSourceMaximum() {
        StubWebClient stub = StubWebClient.serving(klines("\"2026-09-16,10,11,12,9,100\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        provider.kline("sh600519", "1d", 5000);

        assertTrue(stub.lastUrl().contains("lmt=1000"), "来源上限 1000，实际: " + stub.lastUrl());
    }

    /** 不支持的周期必须**在发请求之前**就返回，而不是把 10m 当 klt 发出去。 */
    @Test
    void eastMoneyDoesNotSendARequestForAnUnsupportedInterval() {
        StubWebClient stub = StubWebClient.serving(klines("\"2026-09-16,10,11,12,9,100\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        assertEquals(List.of(), provider.kline("sh600519", "10m", 100));
        assertEquals(List.of(), provider.kline("sh600519", "2h", 100));
        assertEquals(0, stub.requestCount(), "10m 是派生周期，不该发请求");
    }

    @Test
    void eastMoneyParsesTheCommaSeparatedKlines() {
        StubWebClient stub = StubWebClient.serving(klines(
                "\"2026-09-16,1700.5,1710.5,1720.0,1690.0,12345\"",
                "\"2026-09-17,1710.5,1725.0,1730.0,1705.0,23456\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        List<Map<String, Object>> rows = provider.kline("sh600519", "1d", 100);

        assertEquals(2, rows.size());
        assertEquals("2026-09-16", rows.get(0).get("date"));
        assertEquals(1700.5, rows.get(0).get("open"));
        assertEquals(1710.5, rows.get(0).get("close"), "第 2 个字段是收盘，不是最高");
        assertEquals(1720.0, rows.get(0).get("high"));
        assertEquals(1690.0, rows.get(0).get("low"));
        assertEquals(12345.0, rows.get(0).get("volume"));
    }

    /**
     * **一行的畸形不该让整段历史消失。**
     *
     * <p>东方财富在停牌或当根未成形时会给出 {@code "-"}。合并日K与分钟K两份实现时，
     * 日线那份是直接 {@code Double.parseDouble}：一行畸形就抛异常、被吞成"无数据"，
     * 整条K线变成 502 并触发降级与熔断计数。这里保留了分钟级原有的跳过语义。</p>
     */
    @Test
    void eastMoneySkipsAMalformedRowInsteadOfDiscardingTheWholeSeries() {
        StubWebClient stub = StubWebClient.serving(klines(
                "\"2026-09-16,1700.5,1710.5,1720.0,1690.0,12345\"",
                "\"2026-09-17,-,-,-,-,-\"",
                "\"2026-09-18,1710.5,1725.0,1730.0,1705.0,23456\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        List<Map<String, Object>> rows = provider.kline("sh600519", "1d", 100);

        assertEquals(2, rows.size(), "畸形那行被丢掉，其余必须留着");
        assertEquals("2026-09-16", rows.get(0).get("date"));
        assertEquals("2026-09-18", rows.get(1).get("date"));
    }

    /** 成交量缺失记 0.0，但价格齐全的行仍然要留下。 */
    @Test
    void eastMoneyDefaultsAMissingVolumeToZero() {
        StubWebClient stub = StubWebClient.serving(klines("\"2026-09-16,10,11,12,9,-\""));
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(stub.client());

        List<Map<String, Object>> rows = provider.kline("sh600519", "1d", 100);

        assertEquals(1, rows.size(), "成交量缺失不该丢行");
        assertEquals(0.0, rows.get(0).get("volume"));
    }

    @Test
    void eastMoneyTreatsErrorBodiesAsNoDataWithoutThrowing() {
        EastMoneyMarketDataProvider provider = new EastMoneyMarketDataProvider(
                StubWebClient.failing("{\"rc\":1}").client());

        assertEquals(List.of(), provider.kline("sh600519", "1d", 100));
    }

    // ==================== Yahoo：range/interval 与 chart.meta 解析 ====================

    @Test
    void yahooSendsTheRangeThatMatchesTheInterval() {
        StubWebClient stub = StubWebClient.serving(yahooChart(180.5, 178.0, "Apple Inc."));
        YahooMarketDataProvider provider = new YahooMarketDataProvider(stub.client());

        provider.kline("us_stock", "AAPL", "1d", 10);
        assertTrue(stub.lastUrl().contains("range=1y"), "日K取一年，实际: " + stub.lastUrl());
        assertTrue(stub.lastUrl().contains("interval=1d"));
        assertTrue(stub.lastUrl().contains("/AAPL"), "实际: " + stub.lastUrl());

        provider.kline("us_stock", "AAPL", "5m", 10);
        assertTrue(stub.lastUrl().contains("range=5d"), "分钟级取五天，实际: " + stub.lastUrl());
    }

    @Test
    void yahooMapsTheSymbolPerMarket() {
        StubWebClient stub = StubWebClient.serving(yahooChart(180.5, 178.0, "Apple Inc."));
        YahooMarketDataProvider provider = new YahooMarketDataProvider(stub.client());

        provider.quote("us_stock", "BRK.B");
        assertTrue(stub.lastUrl().contains("/BRK-B"), "美股把点换成连字符，实际: " + stub.lastUrl());

        provider.quote("crypto", "BTCUSDT");
        assertTrue(stub.lastUrl().contains("/BTC-USD"), "加密货币换成 -USD，实际: " + stub.lastUrl());
    }

    @Test
    void yahooParsesTheChartMetaIntoTheExtendedQuoteEnvelope() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving(yahooChart(180.5, 178.0, "Apple Inc.")).client());

        Map<String, Object> quote = provider.quote("us_stock", "AAPL");

        assertEquals("AAPL", quote.get("symbol"));
        assertEquals("Apple Inc.", quote.get("name"));
        assertEquals(180.5, quote.get("price"));
        assertEquals(178.0, quote.get("prev_close"));
        assertEquals(2.5, quote.get("change"));
        assertEquals(1.4044943820224718, (Double) quote.get("change_pct"), 1e-9);
        // 1789000000 秒 = 2026-09-10T00:26:40Z。这个值必须算出来，不能凭感觉写。
        assertEquals("2026-09-10T00:26:40Z", quote.get("quote_time"), "市值时间用 Instant 形式");
        assertEquals(7, quote.keySet().size(), "symbol/name/price/prev_close/change/change_pct/quote_time");
    }

    /** 昨收缺失时回退 chartPreviousClose；再缺则 prev_close 为 null 且涨跌为 0。 */
    @Test
    void yahooFallsBackToChartPreviousClose() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving("{\"chart\":{\"result\":[{\"meta\":{"
                        + "\"regularMarketPrice\":180.5,\"chartPreviousClose\":175.0,"
                        + "\"regularMarketTime\":1789000000}}]}}").client());

        Map<String, Object> quote = provider.quote("us_stock", "AAPL");

        assertEquals(175.0, quote.get("prev_close"));
        assertEquals(5.5, quote.get("change"));
    }

    @Test
    void yahooOmitsTheNameWhenTheUpstreamNameIsBlank() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving("{\"chart\":{\"result\":[{\"meta\":{"
                        + "\"regularMarketPrice\":180.5,\"previousClose\":178.0,"
                        + "\"regularMarketTime\":1789000000}}]}}").client());

        Map<String, Object> quote = provider.quote("us_stock", "AAPL");

        assertFalse(quote.containsKey("name"), "名称空白时不该产出该键，由业务层回落到登记名");
    }

    @Test
    void yahooGivesZeroChangeWhenThereIsNoPreviousClose() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving("{\"chart\":{\"result\":[{\"meta\":{"
                        + "\"regularMarketPrice\":180.5,\"regularMarketTime\":1789000000}}]}}").client());

        Map<String, Object> quote = provider.quote("us_stock", "AAPL");

        assertEquals(null, quote.get("prev_close"));
        assertEquals(0.0, quote.get("change"));
        assertEquals(0.0, quote.get("change_pct"));
    }

    @Test
    void yahooTreatsAMissingPriceAsAnError() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving("{\"chart\":{\"result\":[{\"meta\":{}}]}}").client());

        assertTrue(provider.quote("us_stock", "AAPL").containsKey("error"));
    }

    /**
     * K线的行：四条价格缺一即丢行，成交量缺失则**只丢值不丢行**（记 0.0）。
     *
     * <p>Yahoo 的分钟级序列里确实有空档（停牌、无成交），{@code null} 会出现在
     * {@code indicators.quote[0]} 的同下标位置上。这里用 {@code null} 原样模拟，
     * 并把两种情况放在同一段报文里，免得只测到其中一条路径。</p>
     */
    @Test
    void yahooSkipsRowsWithIncompleteOhlcButKeepsTheRest() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving("{\"chart\":{\"result\":[{"
                        + "\"timestamp\":[1789000000,1789000300,1789000600],"
                        + "\"indicators\":{\"quote\":[{\"open\":[10.0,null,12.0],"
                        + "\"close\":[11.0,11.5,13.0],\"high\":[12.0,12.5,14.0],"
                        + "\"low\":[9.0,9.5,11.0],\"volume\":[100,200,null]}]}}]}}").client());

        List<Map<String, Object>> rows = provider.kline("us_stock", "AAPL", "5m", 10);

        assertEquals(2, rows.size(), "缺价那行丢掉，价格齐全但缺量的行必须留下");
        assertEquals(10.0, rows.get(0).get("open"));
        assertEquals(12.0, rows.get(1).get("open"));
        assertEquals(100.0, rows.get(0).get("volume"));
        assertEquals(0.0, rows.get(1).get("volume"), "成交量缺失记 0.0，而不是丢行");
    }

    @Test
    void yahooKeepsOnlyTheLastRowsUpToTheLimit() {
        YahooMarketDataProvider provider = new YahooMarketDataProvider(
                StubWebClient.serving("{\"chart\":{\"result\":[{"
                        + "\"timestamp\":[1,2,3,4,5],"
                        + "\"indicators\":{\"quote\":[{\"open\":[1.0,2.0,3.0,4.0,5.0],"
                        + "\"close\":[1.0,2.0,3.0,4.0,5.0],\"high\":[1.0,2.0,3.0,4.0,5.0],"
                        + "\"low\":[1.0,2.0,3.0,4.0,5.0],\"volume\":[1,2,3,4,5]}]}}]}}").client());

        List<Map<String, Object>> rows = provider.kline("us_stock", "AAPL", "5m", 2);

        assertEquals(2, rows.size());
        assertEquals(4.0, rows.get(0).get("open"), "保留最后 limit 条");
        assertEquals(5.0, rows.get(1).get("open"));
    }

    // ==================== Binance ====================

    @Test
    void binanceNormalizesThePairAndValidatesTheInterval() {
        StubWebClient stub = StubWebClient.serving(
                "[[1789000000000,\"10\",\"12\",\"9\",\"11\",\"100\"]]");
        BinanceMarketDataProvider provider = new BinanceMarketDataProvider(stub.client());

        provider.kline("BTC", "1d", 10);
        assertTrue(stub.lastUrl().contains("symbol=BTCUSDT"), "裸代码要补 USDT，实际: " + stub.lastUrl());

        assertEquals(List.of(), provider.kline("BTCUSDT", "10m", 10), "10m 是派生周期");
        assertEquals(1, stub.requestCount(), "不支持的周期不该发请求");
    }

    /** 币安的K线数组顺序是 开,高,低,收——与东方财富的 开,收,高,低 不同，容易串。 */
    @Test
    void binanceParsesTheOhlcOrderThatBinanceActuallyUses() {
        BinanceMarketDataProvider provider = new BinanceMarketDataProvider(
                StubWebClient.serving("[[1789000000000,\"10\",\"12\",\"9\",\"11\",\"100\"]]").client());

        List<Map<String, Object>> rows = provider.kline("BTCUSDT", "1d", 10);

        assertEquals(1, rows.size());
        assertEquals(10.0, rows.get(0).get("open"));
        assertEquals(12.0, rows.get(0).get("high"));
        assertEquals(9.0, rows.get(0).get("low"));
        assertEquals(11.0, rows.get(0).get("close"), "第 5 个元素才是收盘");
        assertEquals(100.0, rows.get(0).get("volume"));
    }

    @Test
    void binanceTreatsErrorBodiesAsNoDataWithoutThrowing() {
        BinanceMarketDataProvider provider = new BinanceMarketDataProvider(
                StubWebClient.failing("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}").client());

        assertEquals(List.of(), provider.kline("BTCUSDT", "1d", 10));
    }

    @Test
    void binanceQuoteKeepsTheInstantFormQuoteTime() {
        BinanceMarketDataProvider provider = new BinanceMarketDataProvider(
                StubWebClient.serving("{\"lastPrice\":\"65000.0\",\"prevClosePrice\":\"64500.0\","
                        + "\"priceChange\":\"500.0\",\"priceChangePercent\":\"0.78\","
                        + "\"openPrice\":\"65000.0\",\"highPrice\":\"66000.0\",\"lowPrice\":\"64000.0\","
                        + "\"closeTime\":1789000000000}").client());

        Map<String, Object> quote = provider.quote("BTCUSDT");

        assertEquals(65000.0, quote.get("price"));
        assertEquals("2026-09-10T00:26:40Z", quote.get("quote_time"),
                "必须是 UTC 的 Instant 形式——扩展行情信封的约定");
    }

    // ==================== 腾讯：A股主源 ====================

    /**
     * 实时行情 URL 来自**配置**（{@code gold.realtimeUrl} 里带 {@code {symbol}} 占位），
     * 标的必须被替换进去。
     *
     * <p>这里用一份自定义配置，于是"URL 真的来自配置"这件事本身也被验证了——
     * 否则把默认值和硬编码区分不开。</p>
     */
    @Test
    void tencentQuoteUrlComesFromConfigurationWithTheSymbolSubstituted() {
        StubWebClient stub = StubWebClient.servingBytes(tencentASharePayload("sh600519", "MOUTAI"));
        TencentMarketDataProvider provider = tencent(stub, "https://quote.example.test/q={symbol}");

        provider.quote("a_share", "sh600519");

        assertEquals("https://quote.example.test/q=sh600519", stub.lastUrl(),
                "占位符没被替换，或没走配置");
    }

    /**
     * 伦敦金必须问 {@code hf_XAU}。
     *
     * <p>core 的 {@code MarketDataService} 显式传 {@code "hf_XAU"} 而不是 {@code "london_gold"}，
     * 所以这条断言钉的是**两边必须保持一致**：一旦哪边改成传市场名，
     * URL 会变成 {@code q=london_gold}，一个腾讯不认识的代码，实时金价会静默变成错误。</p>
     */
    @Test
    void tencentAsksForHfXauWhenQuotingLondonGold() {
        StubWebClient stub = StubWebClient.servingBytes(tencentASharePayload("hf_XAU", "XAU"));
        TencentMarketDataProvider provider = tencent(stub, "https://quote.example.test/q={symbol}");

        provider.quote("london_gold", "hf_XAU");

        assertEquals("https://quote.example.test/q=hf_XAU", stub.lastUrl());
    }

    /**
     * 腾讯的报文是 **GBK**，Provider 用 {@code new String(bytes, GBK)} 解码。
     *
     * <p>桩按原始字节返回，所以中文名走的是真实解码路径——这条测试才证明得了
     * 编码没有写错。若桩只给字符串，中文会被按默认字符集编一遍，测的是空气。</p>
     */
    @Test
    void tencentDecodesTheGbkQuotePayload() {
        StubWebClient stub = StubWebClient.servingBytes(tencentASharePayload("sh600519", "贵州茅台"));
        TencentMarketDataProvider provider = tencent(stub, "https://quote.example.test/q={symbol}");

        Map<String, Object> quote = provider.quote("a_share", "sh600519");

        assertEquals("贵州茅台", quote.get("name"), "GBK 解码路径");
        assertEquals(1700.0, quote.get("price"));
        assertEquals(1690.0, quote.get("prev_close"));
        assertEquals(1695.0, quote.get("open"));
        assertEquals(10.0, quote.get("change"));
        assertEquals(0.59, quote.get("change_pct"));
        assertEquals(1710.0, quote.get("high"));
        assertEquals(1680.0, quote.get("low"));
    }

    /** A股口径**不产出** {@code source_quote_time}（原有行为，与ETF口径的差别就在这里）。 */
    @Test
    void tencentAShareQuoteDoesNotCarryTheZonedSourceQuoteTime() {
        StubWebClient stub = StubWebClient.servingBytes(tencentASharePayload("sh600519", "MOUTAI"));
        TencentMarketDataProvider provider = tencent(stub, "https://quote.example.test/q={symbol}");

        Map<String, Object> quote = provider.quote("a_share", "sh600519");

        assertFalse(quote.containsKey("source_quote_time"));
        assertFalse(quote.containsKey("error"));
        assertEquals("sh600519", quote.get("symbol"));
    }

    @Test
    void tencentKlineSendsTheDayParamAndFetchLimit() {
        StubWebClient stub = StubWebClient.serving("{\"data\":{\"sh600519\":{\"qfqday\":[]}}}");
        TencentMarketDataProvider provider = tencent(stub, null);

        provider.kline("sh600519", "1d", 10);

        assertTrue(stub.lastUrl().startsWith("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"),
                "实际: " + stub.lastUrl());
        assertTrue(stub.lastUrl().contains("param=sh600519,day,,,500,qfq"),
                "抓取条数与复权口径都是契约的一部分，实际: " + stub.lastUrl());
    }

    /**
     * **非日线周期不发请求。**
     *
     * <p>{@code param} 里硬编码了 {@code day}，interval 入参从来没被用过；不拦住的话
     * 任何周期请求都会拿回日线数据、被当成 5m 用。能力声明只保证它不进降级链，
     * 这个守卫是最后一道。</p>
     */
    @Test
    void tencentKlineRefusesNonDailyIntervalsWithoutRequesting() {
        StubWebClient stub = StubWebClient.serving("{\"data\":{\"sh600519\":{\"qfqday\":[]}}}");
        TencentMarketDataProvider provider = tencent(stub, null);

        assertEquals(List.of(), provider.kline("sh600519", "5m", 10));
        assertEquals(List.of(), provider.kline("sh600519", "10m", 10));
        assertEquals(0, stub.requestCount(), "不该为骗不出来的周期发请求");
    }

    @Test
    void tencentKlineFallsBackToQfqdayWhenDayIsAbsent() {
        StubWebClient stub = StubWebClient.serving("{\"data\":{\"sh600519\":{\"qfqday\":["
                + "[\"2026-09-16\",\"1695.00\",\"1700.00\",\"1710.00\",\"1680.00\",\"12345.00\"]]}}}");
        TencentMarketDataProvider provider = tencent(stub, null);

        List<Map<String, Object>> rows = provider.kline("sh600519", "1d", 10);

        assertEquals(1, rows.size(), "day 缺失时要退到 qfqday");
        assertEquals("2026-09-16", rows.get(0).get("date"));
        assertEquals(1695.0, rows.get(0).get("open"));
        assertEquals(1700.0, rows.get(0).get("close"), "第 3 个元素是收盘");
        assertEquals(1710.0, rows.get(0).get("high"));
        assertEquals(1680.0, rows.get(0).get("low"));
        assertEquals(12345.0, rows.get(0).get("volume"));
    }

    /**
     * 这条是**现状刻画**，不是期望行为。
     *
     * <p>腾讯的K线解析用裸 {@code Double.parseDouble}：一行畸形抛异常 → 被吞成"无数据" →
     * 整条K线为空 → 降级到东方财富或 502。已核对重构前的内联实现，**它也是这样**，
     * 所以这是忠实移植、不是新引入的回归——与东方财富那边不同，那边分钟级原有守卫被合并掉了，
     * 所以修了；这边没有可恢复的守卫。</p>
     *
     * <p>留着这条测试是为了把脆弱性写在明处：腾讯是 A股主源，一行畸形就让整段历史消失，
     * 是否要改成"跳过该行"应当作为一个明确决定来做，而不是顺手改掉主源语义。</p>
     */
    @Test
    void tencentKlineCurrentlyDiscardsTheWholeSeriesOnOneMalformedRow() {
        StubWebClient stub = StubWebClient.serving("{\"data\":{\"sh600519\":{\"qfqday\":["
                + "[\"2026-09-16\",\"1695.00\",\"1700.00\",\"1710.00\",\"1680.00\",\"12345.00\"],"
                + "[\"2026-09-17\",\"-\",\"-\",\"-\",\"-\",\"-\"]]}}}");
        TencentMarketDataProvider provider = tencent(stub, null);

        List<Map<String, Object>> rows = provider.kline("sh600519", "1d", 10);

        assertEquals(List.of(), rows,
                "现状：一行畸形丢掉整个序列（见方法注释：这是待决定项，不是设计）");
    }

    // ==================== 夹具 ====================

    private static TencentMarketDataProvider tencent(StubWebClient stub, String realtimeUrl) {
        JarvisProperties properties = new JarvisProperties();
        if (realtimeUrl != null) {
            properties.getGold().setRealtimeUrl(realtimeUrl);
        }
        return new TencentMarketDataProvider(properties, new ObjectMapper(), stub.client());
    }

    /**
     * 腾讯实时行情的报文：{@code v_sh600519="1~名称~代码~现价~昨收~今开~...~";}
     *
     * <p>下标取自 Provider 自己的文档：name[1] price[3] prev[4] open[5]
     * change[31] pct[32] high[33] low[34]。用数组拼而不是写字面量，
     * 免得数错波浪号。</p>
     */
    private static byte[] tencentASharePayload(String symbol, String name) {
        String[] fields = new String[40];
        Arrays.fill(fields, "0");
        fields[1] = name;
        fields[3] = "1700.00";
        fields[4] = "1690.00";
        fields[5] = "1695.00";
        fields[31] = "10.00";
        fields[32] = "0.59";
        fields[33] = "1710.00";
        fields[34] = "1680.00";
        String payload = "v_" + symbol + "=\"" + String.join("~", fields) + "\";";
        return payload.getBytes(Charset.forName("GBK"));
    }

    private static String klines(String... rows) {
        return "{\"data\":{\"klines\":[" + String.join(",", rows) + "]}}";
    }

    private static String yahooChart(double price, double previous, String name) {
        return "{\"chart\":{\"result\":[{\"meta\":{"
                + "\"regularMarketPrice\":" + price + ","
                + "\"previousClose\":" + previous + ","
                + "\"longName\":\"" + name + "\","
                + "\"regularMarketTime\":1789000000}}]}}";
    }
}