package com.jarvis.research.market;

import com.jarvis.research.service.JdGoldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒级行情 SSE 广播器。
 *
 * <p>全站只由一个 1Hz 调度任务组装一次行情 payload，再 fan-out 给所有浏览器连接。
 * 避免“每个客户端一个定时任务”导致连接数增加时重复访问缓存/数据库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPriceStreamService {

    private static final long EMITTER_TIMEOUT_MS = 5 * 60_000L;

    private final MarketDataService marketDataService;
    private final JdGoldService jdGoldService;
    private final MarketTelemetry telemetry;
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    @Value("${jarvis.market.stream-max-subscribers:2000}")
    private int maxSubscribers = 2000;
    private final AtomicLong sequence = new AtomicLong();

    public synchronized SseEmitter subscribe() {
        if (emitters.size() >= maxSubscribers) {
            telemetry.recordStreamRejected();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "行情流连接已达上限，请稍后重试");
        }
        long id = sequence.incrementAndGet();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.put(id, emitter);
        telemetry.setStreamSubscribers(emitters.size());

        Runnable remove = () -> {
            emitters.remove(id);
            telemetry.setStreamSubscribers(emitters.size());
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(error -> remove.run());

        // 首包立即发送，客户端无需等待下一个整秒 tick。
        if (!send(emitter, buildPayload())) {
            remove.run();
        }
        return emitter;
    }

    /** 全站统一 1Hz 广播。没有订阅者时不读取行情，避免无意义工作。 */
    @Scheduled(fixedRateString = "${jarvis.market.stream-interval-ms:1000}")
    public void broadcast() {
        if (emitters.isEmpty()) return;
        Map<String, Object> payload = buildPayload();
        telemetry.recordStreamBroadcast();
        emitters.forEach((id, emitter) -> {
            if (!send(emitter, payload)) {
                emitters.remove(id);
                telemetry.setStreamSubscribers(emitters.size());
            }
        });
    }

    Map<String, Object> buildPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("market", marketDataService.getLatestPrices());
        payload.put("jd", jdGoldService.latestPrices());
        payload.put("server_time", Instant.now().toString());
        return payload;
    }

    int subscriberCount() {
        return emitters.size();
    }

    private boolean send(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name("prices").data(payload));
            return true;
        } catch (IOException | IllegalStateException e) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 连接已经关闭时 complete 也可能失败；删除 emitter 即可。
            }
            telemetry.recordStreamSendFailure();
            log.debug("秒级行情 SSE 客户端已断开: {}", e.getMessage());
            return false;
        }
    }
}
