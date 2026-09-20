package com.jarvis.research.controller;

import com.jarvis.research.agent.AgentEvent;
import com.jarvis.research.agent.AgentRunService;
import com.jarvis.research.audit.AuditService;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AiRateLimitService;
import com.jarvis.research.service.FeaturePermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** Agent 工作流入口：JWT、权限、配额、审计和运行生命周期均在 Java 边界完成。 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final String FEATURE_KEY = "AI_CHAT_STREAM";

    private final AgentRunService runService;
    private final AiRateLimitService aiRateLimitService;
    private final FeaturePermissionService featurePermissionService;
    private final AuditService auditService;

    /** 保留旧路径，内部已切换为真实 Agent Run 事件流。 */
    @PostMapping(value = "/research/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter research(@RequestBody Map<String, Object> body,
                               jakarta.servlet.http.HttpServletResponse response,
                               HttpServletRequest request) {
        Long userId = CurrentUser.id();
        requireAccess(userId);
        String question = body == null ? "" : String.valueOf(body.getOrDefault("question", ""));
        aiRateLimitService.consume(userId);
        AgentRunService.RunRef run = runService.start(userId, question);
        auditService.record(userId, "AGENT_RUN_START", run.runId(), request.getRemoteAddr(),
                "financial-research-v1");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return runService.openStream(userId, run.runId());
    }

    @GetMapping("/runs")
    public ApiResponse<Object> listRuns() {
        return ApiResponse.ok(runService.list(CurrentUser.id()));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Object> run(@PathVariable String runId) {
        return ApiResponse.ok(runService.detail(CurrentUser.id(), runId));
    }

    @GetMapping("/runs/{runId}/events")
    public ApiResponse<Object> events(@PathVariable String runId) {
        return ApiResponse.ok(runService.events(CurrentUser.id(), runId));
    }

    /** 断线恢复：只重新订阅已有运行，不会创建第二个 Agent Run。 */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
                             jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return runService.openStream(CurrentUser.id(), runId);
    }

    @DeleteMapping("/runs/{runId}")
    public ApiResponse<Object> cancel(@PathVariable String runId, HttpServletRequest request) {
        Long userId = CurrentUser.id();
        runService.cancel(userId, runId);
        auditService.record(userId, "AGENT_RUN_CANCEL", runId, request.getRemoteAddr(), "user_requested");
        return ApiResponse.ok(Map.of("runId", runId, "status", "cancelled"));
    }

    private void requireAccess(Long userId) {
        if (featurePermissionService != null) featurePermissionService.require(userId, FEATURE_KEY);
    }
}
