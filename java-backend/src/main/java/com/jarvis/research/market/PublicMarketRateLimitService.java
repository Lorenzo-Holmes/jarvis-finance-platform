package com.jarvis.research.market;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 公开扩展行情接口限流，保护 Yahoo/Binance/Tencent 等第三方源。
 * 标签只使用固定 endpoint 类型，不记录 IP。
 */
@Service
@RequiredArgsConstructor
public class PublicMarketRateLimitService {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int QUOTE_LIMIT = 180;
    private static final int KLINE_LIMIT = 40;
    private static final int RESOLVE_LIMIT = 30;
    private static final int MAX_TRACKED_IPS = 50_000;

    private final MeterRegistry registry;
    private final Map<String, Window> quoteWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> klineWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> resolveWindows = new ConcurrentHashMap<>();
    private final AtomicLong cleanupTicker = new AtomicLong();

    public void checkQuote(String clientIp) {
        consume(quoteWindows, safe(clientIp), QUOTE_LIMIT, "quote");
    }

    public void checkKline(String clientIp) {
        consume(klineWindows, safe(clientIp), KLINE_LIMIT, "kline");
    }

    public void checkResolve(String clientIp) {
        consume(resolveWindows, safe(clientIp), RESOLVE_LIMIT, "resolve");
    }

    private void consume(Map<String, Window> windows, String key, int limit, String endpoint) {
        Instant now = Instant.now();
        cleanupExpired(windows, now);
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_IPS) {
            recordRejected(endpoint);
            throw limited();
        }
        windows.compute(key, (ignored, current) -> {
            Window active = current;
            if (active == null || !now.isBefore(active.startedAt.plus(WINDOW))) {
                active = new Window(now, 0);
            }
            if (active.count >= limit) {
                recordRejected(endpoint);
                throw limited();
            }
            return new Window(active.startedAt, active.count + 1);
        });
    }

    private void cleanupExpired(Map<String, Window> windows, Instant now) {
        int size = windows.size();
        if (size < 256) return;
        long tick = cleanupTicker.incrementAndGet();
        if (size < MAX_TRACKED_IPS && (tick & 255L) != 0L) return;
        Instant threshold = now.minus(WINDOW);
        windows.entrySet().removeIf(entry -> !entry.getValue().startedAt.isAfter(threshold));
    }

    private void recordRejected(String endpoint) {
        registry.counter("jarvis.market.public.rate.limited", "endpoint", endpoint).increment();
    }

    private ResponseStatusException limited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "公开行情请求过于频繁，请稍后再试");
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record Window(Instant startedAt, int count) {}
}
