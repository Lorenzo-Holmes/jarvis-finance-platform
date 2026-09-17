package com.jarvis.research.market.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataProviderTest {

    @Test
    void mockProviderShouldSupportMockMarket() {
        MockMarketDataProvider provider = new MockMarketDataProvider();

        assertEquals("mock", provider.name());
        assertTrue(provider.supports("mock"));
        assertFalse(provider.supports("gold_etf"));
        assertNotNull(provider.quote("TEST"));
        assertFalse(provider.kline("TEST", "1d", 10).isEmpty());
    }
}
