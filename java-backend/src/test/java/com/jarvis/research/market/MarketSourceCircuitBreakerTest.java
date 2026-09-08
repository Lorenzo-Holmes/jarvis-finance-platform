package com.jarvis.research.market;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSourceCircuitBreakerTest {

    @Test
    void opensAfterThresholdAllowsSingleHalfOpenProbeAndClosesOnSuccess() {
        MarketSourceCircuitBreaker breaker = new MarketSourceCircuitBreaker(
                new SimpleMeterRegistry(), 2, Duration.ZERO, Clock.systemUTC());

        assertTrue(breaker.allowRequest("test.source"));
        breaker.recordFailure("test.source");
        assertEquals("CLOSED", breaker.state("test.source"));

        breaker.recordFailure("test.source");
        assertEquals("OPEN", breaker.state("test.source"));
        assertTrue(breaker.allowRequest("test.source"));
        assertEquals("HALF_OPEN", breaker.state("test.source"));
        assertFalse(breaker.allowRequest("test.source"));

        breaker.recordSuccess("test.source");
        assertEquals("CLOSED", breaker.state("test.source"));
        assertTrue(breaker.allowRequest("test.source"));
    }
}
