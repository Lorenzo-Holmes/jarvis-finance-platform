package com.jarvis.research.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.schedule.ScheduledTask;
import com.jarvis.research.schedule.ScheduledTaskRun;
import com.jarvis.research.schedule.ScheduledTaskService;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.FeaturePermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户定时任务：创建、编辑、启停、删除、立即执行、执行历史。
 *
 * <p>对应 SRS V1.1 / PRD V1.2 的「用户定时任务与任务管理」。</p>
 *
 * <p>⚠️ <strong>命名区分</strong>：本控制器管的是 {@code scheduled_task}（按周期自动重复），
 * 不是 {@code /api/research/tasks}（用户手动发起一次研究请求）。两者中文都叫"任务"，
 * 前端页面文案也刻意分开：本页叫「<strong>定时任务</strong>」，那个叫「研究任务」。</p>
 *
 * <p>权限设计：{@link #FEATURE_KEY} 只拦<strong>会让任务消耗资源</strong>的动作
 * （创建 / 编辑 / 恢复 / 立即执行）。列表、详情、暂停、删除只做归属校验 ——
 * 理由与 {@code ResearchController} 只在 {@code run} 上校验 AI 配额一致：
 * 让"减少资源占用"和"查看自己的东西"永远可用，否则用户一旦被收回功能权限，
 * 连关掉自己那些还在跑的任务都做不到。</p>
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
@RequiredArgsConstructor
@Slf4j
public class ScheduledTaskController {

    /** 定时任务管理能力开关，沿用全大写下划线惯例（与 {@code AI_CHAT} 系列同一套）。 */
    static final String FEATURE_KEY = "TASK_MANAGE";

    private final ScheduledTaskService service;
    private final FeaturePermissionService featurePermissionService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String type,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Page<ScheduledTask> tasks = service.list(CurrentUser.id(), type, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ScheduledTask task : tasks.getContent()) {
            items.add(taskView(task));
        }
        return ApiResponse.ok(pageView(items, tasks));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(taskView(service.get(CurrentUser.id(), id)));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestBody(required = false) ScheduledTaskService.TaskRequest body) {
        featurePermissionService.require(CurrentUser.id(), FEATURE_KEY);
        return ApiResponse.ok(taskView(service.create(CurrentUser.id(), body)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody(required = false) ScheduledTaskService.TaskRequest body) {
        featurePermissionService.require(CurrentUser.id(), FEATURE_KEY);
        return ApiResponse.ok(taskView(service.update(CurrentUser.id(), id, body)));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<Map<String, Object>> pause(@PathVariable Long id) {
        return ApiResponse.ok(taskView(service.pause(CurrentUser.id(), id)));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<Map<String, Object>> resume(@PathVariable Long id) {
        featurePermissionService.require(CurrentUser.id(), FEATURE_KEY);
        return ApiResponse.ok(taskView(service.resume(CurrentUser.id(), id)));
    }

    /**
     * 立即执行一次。
     *
     * <p>返回的是"已受理"而不是执行结果：真正的执行在调度池里异步跑
     * （回测可能是分钟级），结果落在执行历史里。用 200 而不是 202，
     * 是因为前端只需知道"有没有派发成功"，而失败会以 4xx/5xx 明确表达。</p>
     */
    @PostMapping("/{id}/run")
    public ApiResponse<Map<String, Object>> runNow(@PathVariable Long id) {
        featurePermissionService.require(CurrentUser.id(), FEATURE_KEY);
        service.runNow(CurrentUser.id(), id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accepted", true);
        payload.put("message", "已派发执行，结果请查看执行历史");
        return ApiResponse.ok(payload);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        return ApiResponse.ok(taskView(service.delete(CurrentUser.id(), id)));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<Map<String, Object>> runs(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Page<ScheduledTaskRun> history = service.runs(CurrentUser.id(), id, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ScheduledTaskRun run : history.getContent()) {
            items.add(runView(run));
        }
        return ApiResponse.ok(pageView(items, history));
    }

    /**
     * 可选任务类型，带"是否已就绪"。
     *
     * <p>前端据此把还没有执行器的类型置灰 —— 创建接口也会拒掉它们，
     * 但让用户在点下去之前就看到不可选，比收到一个 400 更好。</p>
     */
    @GetMapping("/types")
    public ApiResponse<List<Map<String, Object>>> types() {
        return ApiResponse.ok(service.typeCatalog());
    }

    private Map<String, Object> taskView(ScheduledTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("name", task.getName());
        view.put("task_type", task.getTaskType() == null ? null : task.getTaskType().name());
        view.put("cron_expr", task.getCronExpr());
        view.put("timezone", task.getTimezone());
        view.put("params", readJson(task.getParamsJson()));
        view.put("status", task.getStatus() == null ? null : task.getStatus().name());
        view.put("next_run_at", task.getNextRunAt());
        view.put("last_run_at", task.getLastRunAt());
        view.put("last_run_status", task.getLastRunStatus() == null ? null : task.getLastRunStatus().name());
        view.put("consecutive_failures", task.getConsecutiveFailures());
        view.put("last_error", task.getLastError());
        view.put("created_at", task.getCreatedAt());
        view.put("updated_at", task.getUpdatedAt());
        // 未来几次触发预览：让用户在建任务时就能看出「0 0 9 * * *」到底是几点，
        // 而不是等到第二天发现没跑。算不出来时给空数组，不影响任务本身。
        view.put("next_runs", previewNextRuns(task));
        return view;
    }

    private Map<String, Object> runView(ScheduledTaskRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("task_id", run.getTaskId());
        view.put("trigger_type", run.getTriggerType() == null ? null : run.getTriggerType().name());
        view.put("status", run.getStatus() == null ? null : run.getStatus().name());
        view.put("scheduled_at", run.getScheduledAt());
        view.put("started_at", run.getStartedAt());
        view.put("finished_at", run.getFinishedAt());
        view.put("duration_ms", run.getDurationMs());
        view.put("result_summary", run.getResultSummary());
        view.put("artifacts", readJson(run.getArtifactsJson()));
        view.put("error_type", run.getErrorType());
        view.put("error_message", run.getErrorMessage());
        return view;
    }

    private static Map<String, Object> pageView(List<Map<String, Object>> items, Page<?> page) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("total", page.getTotalElements());
        payload.put("page", page.getNumber());
        payload.put("size", page.getSize());
        return payload;
    }

    /** 与调度内核同一个解析器，保证预览出来的时刻就是它将来真的会触发的时刻。 */
    private List<LocalDateTime> previewNextRuns(ScheduledTask task) {
        List<LocalDateTime> preview = new ArrayList<>();
        if (task.getCronExpr() == null || task.getCronExpr().isBlank()) {
            return preview;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(task.getTimezone() == null ? "Asia/Shanghai" : task.getTimezone());
        } catch (Exception ignored) {
            zone = ZoneId.of("Asia/Shanghai");
        }
        try {
            CronExpression expression = CronExpression.parse(task.getCronExpr());
            LocalDateTime cursor = LocalDateTime.now(zone);
            for (int i = 0; i < ScheduledTaskService.NEXT_RUN_PREVIEW_COUNT; i++) {
                LocalDateTime next = expression.next(cursor);
                if (next == null) {
                    break;
                }
                preview.add(next);
                cursor = next;
            }
        } catch (RuntimeException invalid) {
            // 库里的 cron 只可能被人工改坏；预览失败不该让详情接口整体 500。
            log.warn("任务 cron 无法解析，跳过下次触发预览。taskId={} cron={}",
                    task.getId(), task.getCronExpr());
        }
        return preview;
    }

    /**
     * 把落库的 JSON 字符串读成对象；读不出来时按原文返回，而不是给前端一片空白。
     *
     * <p>⚠️ 这里必须读成 {@code Object} 而不是 {@code Map}：{@code params_json} 是对象、
     * {@code artifacts_json} 却是<strong>数组</strong>（行情扫描的命中列表），
     * 用 {@code Map.class} 解析数组会直接抛异常、被下面的兜底吞成
     * {@code {"raw": "..."}}——数据没错，但前端拿到的是一坨字符串。
     * （2026-09-17 由控制器单测实锤。）</p>
     */
    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("定时任务 JSON 字段无法解析，按原文返回: {}", e.getMessage());
            return Map.of("raw", json);
        }
    }
}
