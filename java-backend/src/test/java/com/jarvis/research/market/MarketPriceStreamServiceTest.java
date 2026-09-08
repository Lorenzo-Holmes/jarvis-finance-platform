package com.jarvis.research.market;

import com.jarvis.research.service.JdGoldService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MarketPriceStreamServiceTest {

    @Test
    void oneBroadcastPayloadIsSharedAcrossSubscribers() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        JdGoldService jdGoldService = mock(JdGoldService.class);
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of("price", 7.88)));
        when(jdGoldService.latestPrices()).thenReturn(Map.of(
                "zheshang", Map.of("price", 812.3)));
        MarketTelemetry telemetry = mock(MarketTelemetry.class);

        MarketPriceStreamService service = new MarketPriceStreamService(marketDataService, jdGoldService, telemetry);

        assertNotNull(service.subscribe());
        assertNotNull(service.subscribe());
        assertEquals(2, service.subscriberCount());
        // 每次 subscribe 只为首包各组装一次 payload。
        verify(marketDataService, times(2)).getLatestPrices();
        verify(jdGoldService, times(2)).latestPrices();

        service.broadcast();

        // 广播阶段只组装一次，而不是按订阅者数量重复读取行情。
        verify(marketDataService, times(3)).getLatestPrices();
        verify(jdGoldService, times(3)).latestPrices();
        verify(telemetry).recordStreamBroadcast();
    }

    @Test
    void rejectsNewSubscriberWhenGlobalCapIsReached() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        JdGoldService jdGoldService = mock(JdGoldService.class);
        MarketTelemetry telemetry = mock(MarketTelemetry.class);
        when(marketDataService.getLatestPrices()).thenReturn(Map.of());
        when(jdGoldService.latestPrices()).thenReturn(Map.of());
        MarketPriceStreamService service = new MarketPriceStreamService(marketDataService, jdGoldService, telemetry);
        ReflectionTestUtils.setField(service, "maxSubscribers", 1);

        service.subscribe();
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, service::subscribe);

        assertEquals(503, error.getStatusCode().value());
        assertEquals(1, service.subscriberCount());
        verify(telemetry).recordStreamRejected();
    }

    @Test
    void broadcastDoesNothingWithoutSubscribers() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        JdGoldService jdGoldService = mock(JdGoldService.class);
        MarketTelemetry telemetry = mock(MarketTelemetry.class);
        MarketPriceStreamService service = new MarketPriceStreamService(marketDataService, jdGoldService, telemetry);

        service.broadcast();

        verifyNoInteractions(marketDataService, jdGoldService);
        verify(telemetry, never()).recordStreamBroadcast();
    }
}
