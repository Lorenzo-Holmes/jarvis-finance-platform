package com.jarvis.research.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.ai.ResearchTask;
import com.jarvis.research.ai.ResearchTaskService;
import com.jarvis.research.ai.ResearchTaskType;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.FeaturePermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究任务：创建、执行、历史、详情。
 *
 * <p>与既有的 {@code /api/ai/*} 透传端点不同，这里的端点有**状态**：
 * 任务落库、可回看、失败有原因。所以返回 Java 自己的 {@link ApiResponse} 信封，
 * 而不是把 Python 的信封原样透传出去——前端不该关心 AI 服务的信封长什么样。</p>
 */
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
@Slf4j
public class ResearchController {

    /** 执行研究属于 AI 能力，按与其他 AI 端点一致的方式做功能开关。 */
    static final String FEATURE_KEY = "AI_RESEARCH";

    private final ResearchTaskService researchTaskService;
    private final FeaturePermissionService featurePermissionService;
    private final ObjectMapper objectMapper;

    @PostMapping("/tasks")
    public ApiResponse<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        ResearchTask task = researchTaskService.create(CurrentUser.id(), new ResearchTaskService.NewTask(
                text(payload.get("title")),
                ResearchTaskType.parse(text(payload.get("task_type"))),
                text(payload.get("market")),
                text(payload.get("symbol")),
                text(payload.get("question"))));
        return ApiResponse.ok(detailView(task));
    }

    /**
     * 执行任务。返回体里的 {@code status} 才是结果——AI 调用失败时这里依然是 200，
     * 因为"执行并记录"这个操作成功了，失败的是研究本身，原因在 {@code error_message} 里。
     * 用 5xx 表达它会让前端分不清"服务坏了"和"这次研究没成功"。
     */
    @PostMapping("/tasks/{id}/run")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long id) {
        featurePermissionService.require(CurrentUser.id(), FEATURE_KEY);
        return ApiResponse.ok(detailView(researchTaskService.run(CurrentUser.id(), id)));
    }

    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ResearchTask> tasks = researchTaskService.history(CurrentUser.id(), page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ResearchTask task : tasks.getContent()) {
            items.add(summaryView(task));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("total", tasks.getTotalElements());
        payload.put("page", tasks.getNumber());
        payload.put("size", tasks.getSize());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(detailView(researchTaskService.get(CurrentUser.id(), id)));
    }

    /**
     * 列表用的轻量视图：**不含上下文与报告**。
     *
     * <p>一条上下文约 8KB，20 条就是 160KB，而列表页一个字节都用不上。
     * 详情接口才返回完整内容。</p>
     */
    private Map<String, Object> summaryView(ResearchTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("title", task.getTitle());
        view.put("task_type", task.getTaskType() == null ? null : task.getTaskType().name());
        view.put("market", task.getMarket());
        view.put("symbol", task.getSymbol());
        view.put("status", task.getStatus() == null ? null : task.getStatus().name());
        view.put("question", task.getQuestion());
        view.put("created_at", task.getCreatedAt());
        view.put("finished_at", task.getFinishedAt());
        return view;
    }

    /** 详情视图：报告解析成嵌套对象给出，前端不必二次解析字符串。 */
    private Map<String, Object> detailView(ResearchTask task) {
        Map<String, Object> view = summaryView(task);
        view.put("model", task.getModel());
        view.put("error_message", task.getErrorMessage());
        view.put("started_at", task.getStartedAt());
        view.put("prompt_tokens", task.getPromptTokens());
        view.put("completion_tokens", task.getCompletionTokens());
        view.put("context", readJson(task.getContextJson()));
        view.put("report", readJson(task.getReportJson()));
        return view;
    }

    /**
     * 把落库的 JSON 字符串读成对象。
     *
     * <p>读不出来时返回 {@code {"raw": ...}} 而不是 null：报告确实存在，
     * 让前端能把它显示出来，比显示一片空白好。（这个字段只可能被人工改库破坏。）</p>
     */
    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("研究任务的 JSON 字段无法解析，按原文返回: {}", e.getMessage());
            return Map.of("raw", json);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}