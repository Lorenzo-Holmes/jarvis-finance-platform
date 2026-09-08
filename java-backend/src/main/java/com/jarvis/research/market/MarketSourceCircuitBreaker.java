package com.jarvis.research.market;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量级行情源熔断器：CLOSED -> OPEN -> HALF_OPEN。
 *
 * <p>状态按固定 source key 隔离；熔断期间不再请求故障源，冷却后只放行一个探测请求。
 * 它只负责源健康状态，不缓存业务报价，便于在不同市场适配器之间复用。</p>
 */
@Component
public class MarketSourceCircuitBreaker {

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int HALF_OPEN = 2;

    private final MeterRegistry registry;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Autowired
    public MarketSourceCircuitBreaker(
            MeterRegistry registry,
            @Value("${jarvis.market.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${jarvis.market.circuit-breaker.open-duration-seconds:30}") long openDurationSeconds) {
        this(registry, failureThreshold, Duration.ofSeconds(openDurationSeconds), Clock.systemUTC());
    }

    MarketSourceCircuitBreaker(MeterRegistry registry, int failureThreshold,
                               Duration openDuration, Clock clock) {
        this.registry = registry;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration.isNegative() ? Duration.ZERO : openDuration;
        this.clock = clock;
    }

    public boolean allowRequest(String source) {
        State state = stateFor(source);
        Instant now = clock.instant();
        synchronized (state) {
            if (state.mode.get() == CLOSED) return true;
            if (state.mode.get() == HALF_OPEN) return false;
            if (now.isBefore(state.openedAt.plus(openDuration))) {
                registry.counter("jarvis.market.source.circuit.rejected", "source", source).increment();
                return false;
            }
            if (!state.probeInFlight.compareAndSet(false, true)) {
                registry.counter("jarvis.market.source.circuit.rejected", "source", source).increment();
                return false;
            }
            state.mode.set(HALF_OPEN);
            return true;
        }
    }

    public void recordSuccess(String source) {
        State state = stateFor(source);
        synchronized (state) {
            state.failures.set(0);
            state.probeInFlight.set(false);
            state.mode.set(CLOSED);
        }
        registry.counter("jarvis.market.source.requests", "source", source, "result", "success").increment();
    }

    public void recordFailure(String source) {
        State state = stateFor(source);
        synchronized (state) {
            state.probeInFlight.set(false);
            int failures = state.failures.incrementAndGet();
            if (state.mode.get() == HALF_OPEN || failures >= failureThreshold) {
                state.mode.set(OPEN);
                state.openedAt = clock.instant();
                registry.counter("jarvis.market.source.circuit.opened", "source", source).increment();
            }
        }
        registry.counter("jarvis.market.source.requests", "source", source, "result", "failure").increment();
    }

    public String state(String source) {
        return switch (stateFor(source).mode.get()) {
            case CLOSED -> "CLOSED";
            case OPEN -> "OPEN";
            default -> "HALF_OPEN";
        };
    }

    private State stateFor(String source) {
        String key = source == null || source.isBlank() ? "unknown" : source;
        return states.computeIfAbsent(key, this::newState);
    }

    private State newState(String source) {
        State state = new State();
        Gauge.builder("jarvis.market.source.circuit.state", state.mode, AtomicInteger::get)
                .tag("source", source)
                .description("Market source circuit state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                .register(registry);
        return state;
    }

    private static final class State {
        private final AtomicInteger mode = new AtomicInteger(CLOSED);
        private final AtomicInteger failures = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean probeInFlight =
                new java.util.concurrent.atomic.AtomicBoolean();
        private Instant openedAt = Instant.EPOCH;
    }
}
