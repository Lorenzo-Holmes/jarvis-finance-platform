package com.jarvis.research.market;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MarketTelemetryTest {

    @Test
    void recordsLowCardinalityQuoteAndStreamMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MarketTelemetry telemetry = new MarketTelemetry(registry);

        telemetry.recordQuote("gold_etf", LocalDateTime.now().minusSeconds(2), false);
        telemetry.recordQuote("gold_etf", LocalDateTime.now().minusSeconds(20), true);
        telemetry.recordFetchFailure("gold_etf");
        telemetry.recordStalePersistSkip("gold_etf");
        telemetry.recordPollCycle("core");
        telemetry.recordPollCycle("jd");
        telemetry.setStreamSubscribers(3);
        telemetry.recordStreamBroadcast();
        telemetry.recordStreamSendFailure();
        telemetry.recordStreamRejected();

        assertEquals(2.0, registry.get("jarvis.market.quote.fetch")
                .tag("market", "gold_etf").tag("result", "success").counter().count());
        assertEquals(1.0, registry.get("jarvis.market.quote.fetch")
                .tag("market", "gold_etf").tag("result", "failure").counter().count());
        assertEquals(1.0, registry.get("jarvis.market.quote.stale.events")
                .tag("market", "gold_etf").counter().count());
        assertEquals(1.0, registry.get("jarvis.market.quote.stale")
                .tag("market", "gold_etf").gauge().value());
        assertEquals(3.0, registry.get("jarvis.market.stream.subscribers").gauge().value());
        assertEquals(1.0, registry.get("jarvis.market.stream.broadcast").counter().count());
        assertEquals(1.0, registry.get("jarvis.market.stream.send.failures").counter().count());
        assertEquals(1.0, registry.get("jarvis.market.stream.rejected").counter().count());
        assertNotNull(registry.get("jarvis.market.quote.source.lag.seconds")
                .tag("market", "gold_etf").gauge());
        assertNotNull(registry.get("jarvis.market.poll.last.run.age.seconds")
                .tag("collector", "core").gauge());
        assertNotNull(registry.get("jarvis.market.poll.last.run.age.seconds")
                .tag("collector", "jd").gauge());
        assertNotNull(registry.get("jarvis.market.stream.last.broadcast.age.seconds").gauge());
    }
}
