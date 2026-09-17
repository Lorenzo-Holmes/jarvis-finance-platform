package com.jarvis.research.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.schedule.ScheduledTask;
import com.jarvis.research.schedule.ScheduledTaskRun;
import com.jarvis.research.schedule.ScheduledTaskService;
import com.jarvis.research.schedule.ScheduledTaskStatus;
import com.jarvis.research.schedule.ScheduledTaskType;
import com.jarvis.research.schedule.TaskRunStatus;
import com.jarvis.research.schedule.TaskTriggerType;
import com.jarvis.research.service.FeaturePermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduledTaskController} 的视图与权限边界。
 *
 * <p>这里钉的核心是一条容易被"统一加个开关"搞错的规则：
 * {@code TASK_MANAGE} <strong>只拦会消耗资源的动作</strong>。
 * 若给列表/暂停/删除也加上，用户一旦被收回权限，就会连"关掉自己在跑的任务"都做不到。</p>
 */
class ScheduledTaskControllerTest {

    private static final long USER = 42L;
    private static final long TASK_ID = 7L;

    private ScheduledTaskService service;
    private FeaturePermissionService permissions;
    private ScheduledTaskController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScheduledTaskService.class);
        permissions = mock(FeaturePermissionService.class);
        controller = new ScheduledTaskController(service, permissions, new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(USER, null));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ 权限边界

    @Test
    void createRequiresTheTaskManageFeature() {
        when(service.create(eq(USER), any())).thenReturn(task());

        controller.create(new ScheduledTaskService.TaskRequest("行情扫描", "MARKET_SCAN", "0 0 9 * * *", null, null));

        verify(permissions).require(USER, "TASK_MANAGE");
    }

    @Test
    void updateResumeAndRunNowAllRequireTheFeature() {
        when(service.update(eq(USER), eq(TASK_ID), any())).thenReturn(task());
        when(service.resume(USER, TASK_ID)).thenReturn(task());

        controller.update(TASK_ID, new ScheduledTaskService.TaskRequest("行情扫描", "MARKET_SCAN", "0 0 9 * * *", null, null));
        controller.resume(TASK_ID);
        controller.runNow(TASK_ID);

        verify(permissions, org.mockito.Mockito.times(3)).require(USER, "TASK_MANAGE");
    }

    @Test
    void listDetailAndPauseDoNotRequireTheFeature() {
        when(service.list(eq(USER), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
        when(service.get(USER, TASK_ID)).thenReturn(task());
        when(service.pause(USER, TASK_ID)).thenReturn(task());
        when(service.delete(USER, TASK_ID)).thenReturn(task());

        controller.list(null, 0, 20);
        controller.detail(TASK_ID);
        controller.pause(TASK_ID);
        controller.delete(TASK_ID);

        // 被收回权限的账号仍必须能看自己的任务、并关掉它们 —— 否则成了"关不掉的任务"。
        verify(permissions, never()).require(any(), any());
    }

    // ------------------------------------------------------------------ 视图

    @Test
    void taskViewExposesTheCronPreviewSoUsersCanSeeWhenItWillActuallyFire() {
        when(service.get(USER, TASK_ID)).thenReturn(task());

        Map<String, Object> data = controller.detail(TASK_ID).getData();

        assertEquals("ACTIVE", data.get("status"));
        assertEquals("0 0 9 * * *", data.get("cron_expr"));
        assertEquals(Map.of("changeThresholdPct", 1.5), data.get("params"),
                "params 要解析成对象，不该把 JSON 字符串穿透给前端");
        @SuppressWarnings("unchecked")
        List<LocalDateTime> preview = (List<LocalDateTime>) data.get("next_runs");
        assertEquals(ScheduledTaskService.NEXT_RUN_PREVIEW_COUNT, preview.size(),
                "预览三次触发，用户才能看出「0 0 9 * * *」不是每天只跑一次以外的含义");
        assertTrue(preview.get(0).isBefore(preview.get(1)));
        assertEquals(9, preview.get(0).getHour());
    }

    @Test
    void taskViewSurvivesAnUnparsableCron() {
        ScheduledTask broken = task();
        broken.setCronExpr("0 0 9 * *");
        when(service.get(USER, TASK_ID)).thenReturn(broken);

        // 库里的 cron 只可能被人工改坏；详情接口不该因此整体 500。
        List<?> preview = (List<?>) controller.detail(TASK_ID).getData().get("next_runs");

        assertTrue(preview.isEmpty());
    }

    @Test
    void listReturnsThePageEnvelope() {
        when(service.list(eq(USER), eq("MARKET_SCAN"), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(task()), PageRequest.of(0, 20), 1));
        when(service.get(USER, TASK_ID)).thenReturn(task());

        Map<String, Object> data = controller.list("MARKET_SCAN", 0, 20).getData();

        assertEquals(1L, data.get("total"));
        assertEquals(0, data.get("page"));
        assertEquals(20, data.get("size"));
        assertEquals(1, ((List<?>) data.get("items")).size());
    }

    @Test
    void runNowReportsAcceptedRatherThanAResult() {
        ApiResponse<Map<String, Object>> response = controller.runNow(TASK_ID);

        assertEquals(Boolean.TRUE, response.getData().get("accepted"));
        // 真正跑在调度池里，结果落在执行历史 —— 这里不能假装返回执行结果。
        assertTrue(response.getData().containsKey("message"));
    }

    @Test
    void runsViewParsesArtifactsAndKeepsTheErrorFields() {
        ScheduledTaskRun failed = ScheduledTaskRun.builder()
                .id(99L)
                .taskId(TASK_ID)
                .triggerType(TaskTriggerType.SCHEDULED)
                .status(TaskRunStatus.FAILED)
                .scheduledAt(LocalDateTime.now().minusMinutes(5))
                .startedAt(LocalDateTime.now().minusMinutes(5))
                .finishedAt(LocalDateTime.now().minusMinutes(4))
                .durationMs(1234L)
                .errorType("java.lang.IllegalStateException")
                .errorMessage("上游行情源超时")
                .idempotencyKey(TASK_ID + ":1")
                .artifactsJson("[{\"market\":\"gold_etf\"}]")
                .build();
        when(service.runs(USER, TASK_ID, 0, 20)).thenReturn(new PageImpl<>(List.of(failed)));

        Map<String, Object> data = controller.runs(TASK_ID, 0, 20).getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) data.get("items")).get(0);

        assertEquals("FAILED", item.get("status"));
        assertEquals("SCHEDULED", item.get("trigger_type"));
        assertEquals(1234L, item.get("duration_ms"));
        assertEquals("java.lang.IllegalStateException", item.get("error_type"));
        assertTrue(item.get("artifacts") instanceof List,
                "产物引用要解析成数组，排障时前端才拿得到具体标的");
    }

    @Test
    void typesEndpointDelegatesToTheService() {
        when(service.typeCatalog()).thenReturn(List.of(Map.of("type", "MARKET_SCAN", "supported", true)));

        assertEquals(1, controller.types().getData().size());
    }

    @Test
    void createToleratesAnEmptyBody() {
        when(service.create(eq(USER), any())).thenReturn(task());

        controller.create(null);

        // 空体会走到服务层的"请求体不能为空" 400，而不是在控制器里先炸成 500。
        verify(service).create(eq(USER), org.mockito.ArgumentMatchers.isNull());
    }

    private static ScheduledTask task() {
        return ScheduledTask.builder()
                .id(TASK_ID)
                .userId(USER)
                .name("每日行情扫描")
                .taskType(ScheduledTaskType.MARKET_SCAN)
                .cronExpr("0 0 9 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson("{\"changeThresholdPct\":1.5}")
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .version(0L)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
    }
}
