package com.jarvis.research.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {

    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final AgentRunRepository runRepository = mock(AgentRunRepository.class);
    private final AgentEventRepository eventRepository = mock(AgentEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pendingAndRunningRunsAreReconciledAfterRestart() {
        AgentRunEntity pending = orphan("pending");
        AgentRunEntity running = orphan("running");
        when(runRepository.findByStatus("pending")).thenReturn(List.of(pending));
        when(runRepository.findByStatus("running")).thenReturn(List.of(running));

        service().reconcileOrphanedRuns();

        assertReconciled(pending);
        assertReconciled(running);
        verify(runRepository).save(pending);
        verify(runRepository).save(running);
    }

    @Test
    void oversizedPayloadIsReplacedByBoundedValidJson() throws Exception {
        AgentRunService service = service();
        Method writer = AgentRunService.class.getDeclaredMethod("writePayload", Map.class);
        writer.setAccessible(true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", "x".repeat(50_000));

        String stored = (String) writer.invoke(service, payload);
        Map<String, Object> parsed = objectMapper.readValue(stored, new TypeReference<>() { });

        assertTrue(stored.length() < 1_000);
        assertEquals(false, parsed.get("available"));
        assertEquals("payload_too_large", parsed.get("reason"));
        assertEquals(true, parsed.get("truncated"));
        assertNotNull(parsed.get("characters"));
    }

    @Test
    void normalPayloadIsKeptForReplay() throws Exception {
        AgentRunService service = service();
        Method writer = AgentRunService.class.getDeclaredMethod("writePayload", Map.class);
        writer.setAccessible(true);
        String stored = (String) writer.invoke(service, Map.of("available", true, "count", 3));
        Map<String, Object> parsed = objectMapper.readValue(stored, new TypeReference<>() { });

        assertEquals(true, parsed.get("available"));
        assertEquals(3, parsed.get("count"));
        assertTrue(stored.length() < 100);
    }

    @Test
    void brokenSseEmitterCanBeClosedWithoutEscapingToTheRequest() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("async context already failed"))
                .when(emitter).complete();
        Method close = AgentRunService.class.getDeclaredMethod("safeComplete", SseEmitter.class);
        close.setAccessible(true);

        assertDoesNotThrow(() -> close.invoke(null, emitter));
    }

    private AgentRunService service() {
        return new AgentRunService(orchestrator, new SimpleAsyncTaskExecutor(), runRepository,
                eventRepository, objectMapper);
    }

    private static AgentRunEntity orphan(String status) {
        return AgentRunEntity.builder()
                .runId("run-" + status)
                .userId(7L)
                .question("检查黄金")
                .status(status)
                .createdAt(LocalDateTime.now())
                .eventCount(1)
                .lastSequence(1L)
                .build();
    }

    private static void assertReconciled(AgentRunEntity run) {
        assertEquals("failed", run.getStatus());
        assertNotNull(run.getFinishedAt());
        assertFalse(run.getErrorMessage().isBlank());
    }
}
