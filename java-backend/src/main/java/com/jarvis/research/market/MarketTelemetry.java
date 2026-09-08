package com.jarvis.research.market;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 核心行情可观测性。
 *
 * <p>指标标签只使用固定市场名，严禁用户 ID、自定义 symbol 等高基数字段进入 Prometheus。</p>
 */
@Slf4j
@Component
public class MarketTelemetry {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> sourceLagMillis = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> staleState = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> pollLastRunEpochMillis = new ConcurrentHashMap<>();
    private final AtomicInteger streamSubscribers = new AtomicInteger();
    private final AtomicLong streamLastBroadcastEpochMillis = new AtomicLong();

    public MarketTelemetry(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("jarvis.market.stream.subscribers", streamSubscribers, AtomicInteger::get)
                .description("Current number of connected market price SSE clients")
                .register(registry);
        Gauge.builder("jarvis.market.stream.last.broadcast.age.seconds", streamLastBroadcastEpochMillis,
                        MarketTelemetry::ageSeconds)
                .description("Seconds since the last market SSE broadcast; 0 until the first broadcast")
                .register(registry);
    }

    public void recordQuote(String market, LocalDateTime quoteTime, boolean stale) {
        registry.counter("jarvis.market.quote.fetch", "market", market, "result", "success").increment();
        AtomicInteger staleGauge = staleState.computeIfAbsent(market, key -> {
            AtomicInteger state = new AtomicInteger();
            Gauge.builder("jarvis.market.quote.stale", state, AtomicInteger::get)
                    .tag("market", key)
                    .description("Whether the latest core quote is stale (1) or fresh (0)")
                    .register(registry);
            return state;
        });
        staleGauge.set(stale ? 1 : 0);

        if (quoteTime != null) {
            long lag = Math.max(0L, Duration.between(quoteTime, LocalDateTime.now()).toMillis());
            AtomicLong lagGauge = sourceLagMillis.computeIfAbsent(market, key -> {
                AtomicLong value = new AtomicLong();
                Gauge.builder("jarvis.market.quote.source.lag.seconds", value,
                                current -> current.get() / 1000.0)
                        .tag("market", key)
                        .description("Lag between source quote timestamp and server time in seconds")
                        .register(registry);
                return value;
            });
            lagGauge.set(lag);
        }
        if (stale) {
            registry.counter("jarvis.market.quote.stale.events", "market", market).increment();
        }
    }

    public void recordFetchFailure(String market) {
        registry.counter("jarvis.market.quote.fetch", "market", market, "result", "failure").increment();
    }

    public void recordSourceSwitch(String market, String source) {
        registry.counter("jarvis.market.source.switch", "market", market, "source", source).increment();
    }

    /** Scheduler heartbeat, independent of whether a market is currently open. */
    public void recordPollCycle(String collector) {
        AtomicLong lastRun = pollLastRunEpochMillis.computeIfAbsent(collector, key -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("jarvis.market.poll.last.run.age.seconds", value, MarketTelemetry::ageSeconds)
                    .tag("collector", key)
                    .description("Seconds since the last market collector scheduling cycle; 0 until first run")
                    .register(registry);
            return value;
        });
        lastRun.set(System.currentTimeMillis());
    }

    public void recordStalePersistSkip(String market) {
        registry.counter("jarvis.market.quote.persist.skipped", "market", market, "reason", "stale").increment();
    }

    public void setStreamSubscribers(int count) {
        streamSubscribers.set(Math.max(0, count));
    }

    public void recordStreamBroadcast() {
        registry.counter("jarvis.market.stream.broadcast").increment();
        streamLastBroadcastEpochMillis.set(System.currentTimeMillis());
    }

    public void recordStreamSendFailure() {
        registry.counter("jarvis.market.stream.send.failures").increment();
    }

    public void recordStreamRejected() {
        registry.counter("jarvis.market.stream.rejected").increment();
    }

    private static double ageSeconds(AtomicLong epochMillis) {
        long value = epochMillis.get();
        if (value <= 0L) return 0.0;
        return Math.max(0L, System.currentTimeMillis() - value) / 1000.0;
    }
}
