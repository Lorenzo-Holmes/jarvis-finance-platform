package com.jarvis.research.market.provider;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

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

    @Test
    void allProvidersAreSpringComponents() {
        assertNotNull(YahooMarketDataProvider.class.getAnnotation(Component.class));
        assertNotNull(EastMoneyMarketDataProvider.class.getAnnotation(Component.class));
        assertNotNull(BinanceMarketDataProvider.class.getAnnotation(Component.class));
        assertNotNull(SinaMarketDataProvider.class.getAnnotation(Component.class));
    }

    // ==================== Yahoo ====================

    @Test
    void yahooContract() {
        assertEquals("Yahoo", yahoo.name());
        assertEquals(20, yahoo.priority());
        assertEquals("Yahoo Finance (GC=F 期货)", yahoo.displayName());

        assertTrue(yahoo.supports("london_gold"));
        assertTrue(yahoo.supports("us_stock"));
        assertTrue(yahoo.supports("LONDON_GOLD"));
        assertFalse(yahoo.supports("gold_etf"));
        assertFalse(yahoo.supports("a_share"));
        assertFalse(yahoo.supports("crypto"));
        assertFalse(yahoo.supports(null));

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
        assertFalse(eastMoney.supportsKline("a_share"));
        assertFalse(eastMoney.supportsKline("gold_etf"));

        assertEquals(List.of(), eastMoney.kline("sh518850", "1d", 10));
    }

    @Test
    void eastMoneyBlankSymbolReturnsErrorWithoutNetwork() {
        Map<String, Object> quote = eastMoney.quote("  ");
        assertTrue(quote.containsKey("error"));
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