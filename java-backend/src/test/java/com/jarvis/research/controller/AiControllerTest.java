package com.jarvis.research.controller;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import com.jarvis.research.service.FeaturePermissionService;
import com.jarvis.research.service.SimTradeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chatInjectsServerOwnedMarketAndPortfolioContext() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        FeaturePermissionService permissions = mock(FeaturePermissionService.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        SimTradeService simTradeService = mock(SimTradeService.class);
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of("price", 7.88)));
        when(marketDataService.getDailyKline(any(), eq(60))).thenReturn(Map.of("data", java.util.List.of()));
        when(simTradeService.getAccountOverview(42L)).thenReturn(Map.of(
                "cash", 90000, "positions", Map.of()));
        when(proxy.post(eq("/api/ai/chat"), any())).thenReturn(Map.of("code", 200));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        AiController controller = new AiController(
                proxy, rateLimit, permissions, marketDataService, simTradeService);
        controller.chat(Map.of(
                "messages", java.util.List.of(Map.of("role", "user", "content", "我的风险如何")),
                "research_context", Map.of("forged", true)));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(proxy).post(eq("/api/ai/chat"), bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("research_context");
        assertNotNull(context.get("generated_at"));
        assertEquals(Map.of("gold_etf", Map.of("price", 7.88)), context.get("prices"));
        assertEquals(Map.of("cash", 90000, "positions", Map.of()), context.get("portfolio"));
        assertFalse(context.containsKey("forged"));
        verify(simTradeService).getAccountOverview(42L);
        verify(rateLimit).consume(42L);
    }

    @Test
    void streamingChatConsumesQuotaAndAddsStreamingHeaders() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        when(proxy.stream(eq("/api/ai/chat/stream"), any()))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"type\":\"delta\",\"content\":\"ok\"}")
                                .event("delta").build(),
                        ServerSentEvent.builder("{\"type\":\"done\"}")
                                .event("done").build()
                ));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        AiController controller = new AiController(proxy, rateLimit);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseEmitter emitter = controller.chatStream(
                Map.of("messages", java.util.List.of(Map.of("role", "user", "content", "hi"))),
                response
        );

        assertNotNull(emitter);
        assertEquals("no-cache, no-transform", response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        verify(rateLimit).consume(42L);
        verify(proxy).stream(eq("/api/ai/chat/stream"), any());
    }
}
