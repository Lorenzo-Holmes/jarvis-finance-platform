package com.jarvis.research.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 模拟交易与风控低基数业务指标；不得使用 userId 作为标签。 */
@Component
@RequiredArgsConstructor
public class SimTradingTelemetry {

    private final MeterRegistry registry;

    public void recordOrder(String type, boolean replay) {
        registry.counter("jarvis.sim.orders",
                "type", normalizeType(type),
                "result", replay ? "idempotent_replay" : "success")
                .increment();
    }

    public void recordRiskAccountScan() {
        registry.counter("jarvis.sim.risk.account.scans").increment();
    }

    public void recordRiskStaleSkip() {
        registry.counter("jarvis.sim.risk.stale.skips").increment();
    }

    public void recordLiquidation() {
        registry.counter("jarvis.sim.liquidations").increment();
    }

    private String normalizeType(String type) {
        return "SELL".equalsIgnoreCase(type) ? "SELL" : "BUY";
    }
}
