package com.jarvis.research.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent Run 生命周期与 SSE 重放服务。
 *
 * <p>数据库保存运行和事件的完整历史；内存只保存当前 JVM 的取消句柄与 SSE 订阅器。
 * 因此浏览器断线后可以从 PostgreSQL 重放，服务重启后也不会把历史运行整体丢掉。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private static final int MAX_EVENTS = 120;
    private static final int MAX_PAYLOAD_JSON_LENGTH = 38_000;

    private final AgentOrchestrator orchestrator;
    private final AsyncTaskExecutor agentTaskExecutor;
    private final AgentRunRepository runRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, RunState> activeRuns = new ConcurrentHashMap<>();

    /** 进程异常退出后，未完成运行不应在历史抽屉里永久显示为运行中。 */
    @PostConstruct
    void reconcileOrphanedRuns() {
        reconcileStatus("pending");
        reconcileStatus("running");
    }

    private void reconcileStatus(String status) {
        for (AgentRunEntity run : runRepository.findByStatus(status)) {
            run.setStatus("failed");
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage("服务重启时运行未完成，可从已保存事件恢复查看");
            runRepository.save(run);
        }
    }

    public RunRef start(Long userId, String question) {
        String normalized = normalizeQuestion(question);
        String runId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AgentRunEntity entity = AgentRunEntity.builder()
                .runId(runId)
                .userId(userId)
                .question(normalized)
                .status("pending")
                .createdAt(now)
                .startedAt(now)
                .eventCount(0)
                .lastSequence(0L)
                .build();
        runRepository.save(entity);

        RunState state = new RunState(runId, userId, normalized, now.toInstant(ZoneOffset.UTC));
        activeRuns.put(runId, state);
        try {
            state.future = agentTaskExecutor.submit(() -> orchestrator.run(
                    state.userId, state.runId, state.question, event -> publish(state, event),
                    state.cancelled::get));
        } catch (RuntimeException error) {
            activeRuns.remove(runId, state);
            entity.setStatus("failed");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setErrorMessage(safeMessage(error));
            runRepository.save(entity);
            throw error;
        }
        return state.ref("pending");
    }

    public SseEmitter openStream(Long userId, String runId) {
        ownedRun(userId, runId);
        SseEmitter emitter = new SseEmitter(120_000L);
        RunState state = activeRuns.get(runId);
        synchronized (state == null ? this : state) {
            try {
                // 在订阅锁内重新读取，避免“历史已重放但终态事件恰好在此时落库”
                // 导致订阅器被加入后永远收不到 completion 的竞态。
                AgentRunEntity current = ownedRun(userId, runId);
                List<AgentEvent> history = eventsFor(runId);
                for (AgentEvent event : history) send(emitter, event);
                if (isTerminal(current.getStatus()) || state == null) {
                    emitter.complete();
                    return emitter;
                }
                state.subscribers.add(emitter);
            } catch (Exception error) {
                emitter.completeWithError(error);
                return emitter;
            }
        }
        emitter.onCompletion(() -> state.subscribers.remove(emitter));
        emitter.onTimeout(() -> state.subscribers.remove(emitter));
        emitter.onError(error -> state.subscribers.remove(emitter));
        return emitter;
    }

    public Map<String, Object> detail(Long userId, String runId) {
        AgentRunEntity run = ownedRun(userId, runId);
        return snapshot(run, eventsFor(runId));
    }

    public List<AgentEvent> events(Long userId, String runId) {
        ownedRun(userId, runId);
        return eventsFor(runId);
    }

    public List<Map<String, Object>> list(Long userId) {
        return runRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .stream()
                .map(run -> snapshot(run, List.of()))
                .toList();
    }

    public void cancel(Long userId, String runId) {
        AgentRunEntity entity = ownedRun(userId, runId);
        RunState state = activeRuns.get(runId);
        if (state == null) {
            if (isTerminal(entity.getStatus())) return;
            state = new RunState(runId, userId, entity.getQuestion(), toInstant(entity.getCreatedAt()));
            state.sequence = entity.getLastSequence() == null ? 0L : entity.getLastSequence();
        }
        synchronized (state) {
            AgentRunEntity current = ownedRun(userId, runId);
            if (isTerminal(current.getStatus())) return;
            state.cancelled.set(true);
            publish(state, AgentEvent.create("run_cancelled", "cancelled", "研究运行已停止", null,
                    null, "后续工具调用已停止", Map.of(), Instant.now(), Instant.now(), 0L,
                    "CANCELLED"));
            Future<?> future = state.future;
            if (future != null) future.cancel(true);
            completeSubscribers(state);
        }
    }

    private void publish(RunState state, AgentEvent raw) {
        synchronized (state) {
            if (state.sequence >= MAX_EVENTS) return;
            AgentEvent event = raw.withRun(state.runId, ++state.sequence);
            persist(state, event);
            for (SseEmitter emitter : state.subscribers) {
                try {
                    send(emitter, event);
                } catch (Exception error) {
                    state.subscribers.remove(emitter);
                    emitter.completeWithError(error);
                }
            }
            if (isTerminalEvent(event)) {
                completeSubscribers(state);
                // 终态事件已经完整写入 PostgreSQL；不再保留取消句柄和订阅器，
                // 后续重连会直接从历史重放，避免长时间运行进程的内存增长。
                activeRuns.remove(state.runId, state);
            }
        }
    }

    private void persist(RunState state, AgentEvent event) {
        AgentEventEntity eventEntity = AgentEventEntity.builder()
                .runId(event.runId())
                .stepId(event.stepId())
                .sequence(event.sequence())
                .eventType(event.type())
                .status(event.status())
                .title(event.title())
                .tool(event.tool())
                .inputSummary(event.inputSummary())
                .outputSummary(event.outputSummary())
                .payloadJson(writePayload(event.payload()))
                .startedAt(toLocalDateTime(event.startedAt()))
                .finishedAt(toLocalDateTime(event.finishedAt()))
                .durationMs(event.durationMs())
                .errorCode(event.errorCode())
                .build();
        eventRepository.save(eventEntity);

        AgentRunEntity run = runRepository.findById(state.runId)
                .orElseThrow(() -> new IllegalStateException("Agent 运行记录不存在"));
        String currentStatus = run.getStatus();
        if ("run_started".equals(event.type())) {
            run.setStatus("running");
        } else if ("run_completed".equals(event.type())) {
            run.setStatus("completed");
        } else if ("run_failed".equals(event.type())) {
            run.setStatus("failed");
        } else if ("run_cancelled".equals(event.type())) {
            run.setStatus("cancelled");
        } else if ("pending".equals(currentStatus)) {
            run.setStatus("running");
        }
        run.setEventCount(event.sequence() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) event.sequence());
        run.setLastSequence(event.sequence());
        if ("failed".equals(event.status())) run.setErrorMessage(trim(event.outputSummary(), 1000));
        if (isTerminalEvent(event)) run.setFinishedAt(toLocalDateTime(event.finishedAt() == null ? Instant.now() : event.finishedAt()));
        runRepository.save(run);
    }

    private List<AgentEvent> eventsFor(String runId) {
        return eventRepository.findByRunIdOrderBySequenceAsc(runId).stream()
                .map(this::toProtocol)
                .toList();
    }

    private AgentEvent toProtocol(AgentEventEntity entity) {
        return new AgentEvent(entity.getRunId(), entity.getStepId(), entity.getSequence(),
                entity.getEventType(), entity.getStatus(), entity.getTitle(), entity.getTool(),
                entity.getInputSummary(), entity.getOutputSummary(), readPayload(entity.getPayloadJson()),
                toInstant(entity.getStartedAt()), toInstant(entity.getFinishedAt()),
                entity.getDurationMs(), entity.getErrorCode());
    }

    private Map<String, Object> snapshot(AgentRunEntity run, List<AgentEvent> history) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("runId", run.getRunId());
        out.put("status", run.getStatus());
        out.put("question", run.getQuestion());
        out.put("createdAt", toInstant(run.getCreatedAt()));
        out.put("eventCount", run.getEventCount() == null ? 0 : run.getEventCount());
        if (!history.isEmpty()) out.put("lastEvent", history.get(history.size() - 1));
        return out;
    }

    private AgentRunEntity ownedRun(Long userId, String runId) {
        return runRepository.findByRunIdAndUserId(runId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 运行不存在或已过期"));
    }

    private void send(SseEmitter emitter, AgentEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.sequence()))
                .name("agent_step")
                .data(event));
    }

    private void completeSubscribers(RunState state) {
        for (SseEmitter emitter : state.subscribers) emitter.complete();
        state.subscribers.clear();
    }

    private static boolean isTerminalEvent(AgentEvent event) {
        return "run_completed".equals(event.type()) || "run_failed".equals(event.type())
                || "run_cancelled".equals(event.type());
    }

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private static String normalizeQuestion(String question) {
        String value = question == null ? "" : question.trim();
        if (value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "研究问题不能为空");
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            if (json.length() <= MAX_PAYLOAD_JSON_LENGTH) return json;
            return objectMapper.writeValueAsString(Map.of(
                    "available", false,
                    "reason", "payload_too_large",
                    "truncated", true,
                    "characters", json.length()));
        } catch (JsonProcessingException error) {
            return "{\"available\":false,\"reason\":\"payload_serialization_failed\"}";
        }
    }

    private Map<String, Object> readPayload(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(payload, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of("available", false, "reason", "payload_parse_failed");
        }
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "未知错误" : error.getMessage();
        if (message == null || message.isBlank()) return "执行失败，请稍后重试";
        return trim(message.replaceAll("(?i)(authorization|token|api[-_]?key|cookie)\\s*[:=]\\s*\\S+", "$1=[已隐藏]"), 240);
    }

    public record RunRef(String runId, String status, String question, Instant createdAt) {
    }

    private static final class RunState {
        private final String runId;
        private final Long userId;
        private final String question;
        private final Instant createdAt;
        private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private long sequence;
        private volatile Future<?> future;

        private RunState(String runId, Long userId, String question, Instant createdAt) {
            this.runId = runId;
            this.userId = userId;
            this.question = question;
            this.createdAt = createdAt;
        }

        private RunRef ref(String status) {
            return new RunRef(runId, status, question, createdAt);
        }
    }
}
