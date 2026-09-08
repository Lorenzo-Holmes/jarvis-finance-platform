package com.jarvis.research.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimTradingTelemetryTest {

    @Test
    void recordsBusinessMetricsWithoutUserLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SimTradingTelemetry telemetry = new SimTradingTelemetry(registry);

        telemetry.recordOrder("BUY", false);
        telemetry.recordOrder("BUY", true);
        telemetry.recordOrder("SELL", false);
        telemetry.recordRiskAccountScan();
        telemetry.recordRiskStaleSkip();
        telemetry.recordLiquidation();

        assertEquals(1.0, registry.get("jarvis.sim.orders")
                .tag("type", "BUY").tag("result", "success").counter().count());
        assertEquals(1.0, registry.get("jarvis.sim.orders")
                .tag("type", "BUY").tag("result", "idempotent_replay").counter().count());
        assertEquals(1.0, registry.get("jarvis.sim.orders")
                .tag("type", "SELL").tag("result", "success").counter().count());
        assertEquals(1.0, registry.get("jarvis.sim.risk.account.scans").counter().count());
        assertEquals(1.0, registry.get("jarvis.sim.risk.stale.skips").counter().count());
        assertEquals(1.0, registry.get("jarvis.sim.liquidations").counter().count());
    }
}
