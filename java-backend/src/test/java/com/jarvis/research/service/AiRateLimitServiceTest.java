package com.jarvis.research.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AiRateLimitServiceTest {

    private final AiQuotaService quotaService = mock(AiQuotaService.class);

    @Test
    void limitsEleventhRequestWithinSameMinute() {
        AiRateLimitService service = new AiRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.consume(42L);
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.consume(42L));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void quotasAreIndependentBetweenUsers() {
        AiRateLimitService service = new AiRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.consume(1L);
        }
        service.consume(2L);
    }

    // ==================== token 记账 ====================

    /**
     * 本类的 token 记账曾经**静默失效**：原实现只看顶层的 {@code usage}，
     * 而唯一的调用方传进来的是 Python 信封 {@code {code,message,data:{...,usage}}}。
     * 后果不只是统计为零——{@link AiQuotaService#consumeRequest} 靠
     * {@code monthlyTokenUsed} 拦截请求，所以月度 token 配额从未生效。
     * 之所以长期没被发现，是因为一条测试都没有覆盖这个方法。下面这几条就是钉住形状的。
     */
    @Test
    void tokensAreRecordedFromTheDataEnvelopeThatCallersActuallyPass() {
        Map<String, Object> envelope = Map.of(
                "code", 200,
                "message", "ok",
                "data", Map.of(
                        "content", "回答",
                        "model", "deepseek-x",
                        "usage", Map.of("total_tokens", 1234)));

        new AiRateLimitService(quotaService).recordTokens(42L, envelope);

        verify(quotaService).consumeTokens(42L, 1234L);
    }

    @Test
    void tokensAreAlsoRecordedFromAFlatUsageMap() {
        new AiRateLimitService(quotaService)
                .recordTokens(42L, Map.of("usage", Map.of("total_tokens", 500)));

        verify(quotaService).consumeTokens(42L, 500L);
    }

    @Test
    void theNestedEnvelopeWinsWhenBothShapesArePresent() {
        Map<String, Object> envelope = Map.of(
                "usage", Map.of("total_tokens", 1),
                "data", Map.of("usage", Map.of("total_tokens", 999)));

        new AiRateLimitService(quotaService).recordTokens(42L, envelope);

        verify(quotaService).consumeTokens(42L, 999L);
    }

    @Test
    void aMissingUsageBlockIsNotAnError() {
        AiRateLimitService service = new AiRateLimitService(quotaService);

        assertDoesNotThrow(() -> service.recordTokens(42L, Map.of("code", 200, "data", Map.of())));
        assertDoesNotThrow(() -> service.recordTokens(42L, Map.of()));
        assertDoesNotThrow(() -> service.recordTokens(42L, "不是一个 map"));
        assertDoesNotThrow(() -> service.recordTokens(42L, null));
        assertDoesNotThrow(() -> service.recordTokens(42L, Map.of("data", "不是 map")));

        verify(quotaService, never()).consumeTokens(anyLong(), anyLong());
    }

    /** 上游没给 usage 的兼容模型不该影响调用结果，也不该记成 0。 */
    @Test
    void usageWithoutATotalTokenCountRecordsNothing() {
        AiRateLimitService service = new AiRateLimitService(quotaService);

        service.recordTokens(42L, Map.of("data", Map.of("usage", Map.of("prompt_tokens", 10))));
        service.recordTokens(42L, Map.of("data", Map.of("usage", Map.of("total_tokens", "abc"))));

        verify(quotaService, never()).consumeTokens(anyLong(), anyLong());
    }

    @Test
    void stringEncodedTokenCountsAreAccepted() {
        new AiRateLimitService(quotaService)
                .recordTokens(42L, Map.of("data", Map.of("usage", Map.of("total_tokens", "2048"))));

        verify(quotaService).consumeTokens(42L, 2048L);
    }

    /** 没有配额服务（不加载容器时的轻量实例）时静默跳过，而不是 NPE。 */
    @Test
    void withoutAQuotaServiceNothingHappens() {
        assertDoesNotThrow(() -> new AiRateLimitService()
                .recordTokens(42L, Map.of("data", Map.of("usage", Map.of("total_tokens", 10)))));
    }

    // ==================== 与持久化配额的衔接 ====================

    @Test
    void thePersistedQuotaIsConsultedOnEveryConsume() {
        AiRateLimitService service = new AiRateLimitService(quotaService);

        for (int i = 0; i < 10; i++) {
            service.consume(42L);
        }

        verify(quotaService, times(10)).consumeRequest(42L);
    }

    /** 持久化配额抛出的 429 要原样冒出来，不能被吞掉。 */
    @Test
    void thePersistedQuotaRefusalPropagates() {
        AiQuotaService refusing = mock(AiQuotaService.class);
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日 AI 请求额度已用完"))
                .when(refusing).consumeRequest(42L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AiRateLimitService(refusing).consume(42L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
    }

    /** 并发下漏计数会让限流形同虚设——这是 synchronized 的存在理由。 */
    @Test
    void concurrentConsumeDoesNotLoseCounts() throws InterruptedException {
        AiRateLimitService service = new AiRateLimitService(quotaService);
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                for (int call = 0; call < 5; call++) {
                    try {
                        service.consume(7L);
                    } catch (ResponseStatusException e) {
                        rejected.incrementAndGet();
                    }
                }
            });
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        // 8 线程 × 5 次 = 40 次尝试，上限 10 次/分钟 → 恰好 30 次被拒
        assertEquals(30, rejected.get(), "并发下漏计数会让限流形同虚设");
        verify(quotaService, times(10)).consumeRequest(7L);
    }
}