package com.jarvis.research.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

/** Agent 对外事件协议，只包含可展示摘要和脱敏 payload。 */
public record AgentEvent(
        String runId,
        String stepId,
        long sequence,
        String type,
        String status,
        String title,
        String tool,
        String inputSummary,
        String outputSummary,
        Map<String, Object> payload,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorCode
) {
    public AgentEvent {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static AgentEvent create(String type, String status, String title, String tool,
                                    String inputSummary, String outputSummary,
                                    Map<String, Object> payload, Instant startedAt,
                                    Instant finishedAt, Long durationMs, String errorCode) {
        return new AgentEvent(null, UUID.randomUUID().toString(), 0L, type, status, title,
                tool, inputSummary, outputSummary, safePayload(payload), startedAt,
                finishedAt, durationMs, errorCode);
    }

    public AgentEvent withRun(String nextRunId, long nextSequence) {
        return new AgentEvent(nextRunId, stepId, nextSequence, type, status, title, tool,
                inputSummary, outputSummary, payload, startedAt, finishedAt, durationMs, errorCode);
    }

    private static Map<String, Object> safePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return Map.of();
        return new LinkedHashMap<>(payload);
    }
}
