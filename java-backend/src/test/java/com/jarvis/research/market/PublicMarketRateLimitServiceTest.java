package com.jarvis.research.market;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicMarketRateLimitServiceTest {

    @Test
    void quoteEndpointAllowsNormalOneHertzUsageButCapsAbuse() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PublicMarketRateLimitService service = new PublicMarketRateLimitService(registry);

        for (int i = 0; i < 180; i++) service.checkQuote("203.0.113.10");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.checkQuote("203.0.113.10"));

        assertEquals(429, error.getStatusCode().value());
        assertEquals(1.0, registry.get("jarvis.market.public.rate.limited")
                .tag("endpoint", "quote").counter().count());
    }

    @Test
    void klineLimitIsIndependentPerIp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PublicMarketRateLimitService service = new PublicMarketRateLimitService(registry);

        for (int i = 0; i < 40; i++) service.checkKline("198.51.100.1");
        service.checkKline("198.51.100.2");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.checkKline("198.51.100.1"));

        assertEquals(429, error.getStatusCode().value());
    }
}
