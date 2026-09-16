package com.jarvis.research.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.ai.ResearchTask;
import com.jarvis.research.ai.ResearchTaskService;
import com.jarvis.research.ai.ResearchTaskStatus;
import com.jarvis.research.ai.ResearchTaskType;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.service.FeaturePermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResearchController} 的视图与委派行为。
 *
 * <p>控制器本身很薄，所以这里钉的主要是**接口形状上的决定**：
 * 列表不带上下文/报告、详情把落库的 JSON 解析成对象、
 * 执行前先过功能开关、越权时 404 原样冒出去。</p>
 */
class ResearchControllerTest {

    private static final long USER = 42L;

    private ResearchTaskService service;
    private FeaturePermissionService permissions;
    private ResearchController controller;

    @BeforeEach
    void setUp() {
        service = mock(ResearchTaskService.class);
        permissions = mock(FeaturePermissionService.class);
        controller = new ResearchController(service, permissions, new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(USER, null));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPassesTheRequestThroughAndReturnsTheTaskView() {
        when(service.create(eq(USER), any())).thenReturn(task(ResearchTaskStatus.PENDING));

        ApiResponse<Map<String, Object>> response = controller.create(Map.of(
                "title", "贵州茅台研究",
                "task_type", "RISK",
                "market", "a_share",
                "symbol", "sh600519",
                "question", "值得关注吗？"));

        assertEquals(1L, response.getData().get("id"));
        assertEquals("PENDING", response.getData().get("status"));
        assertEquals("REPORT", response.getData().get("task_type"),
                "视图里的类型名取自实体，而不是请求里的原始字符串");

        org.mockito.ArgumentCaptor<ResearchTaskService.NewTask> captor =
                org.mockito.ArgumentCaptor.forClass(ResearchTaskService.NewTask.class);
        verify(service).create(eq(USER), captor.capture());
        assertEquals("RISK", captor.getValue().taskType().name());
        assertEquals("sh600519", captor.getValue().symbol());
    }

    @Test
    void createToleratesAnEmptyBody() {
        when(service.create(eq(USER), any())).thenReturn(task(ResearchTaskStatus.PENDING));

        controller.create(null);

        verify(service).create(eq(USER), any());
    }

    @Test
    void runChecksTheFeatureSwitchBeforeDoingAnything() {
        when(service.run(USER, 1L)).thenReturn(task(ResearchTaskStatus.SUCCEEDED));

        controller.run(1L);

        verify(permissions).require(USER, ResearchController.FEATURE_KEY);
        verify(service).run(USER, 1L);
    }

    /** 功能开关拒绝时根本不该去跑任务。 */
    @Test
    void runDoesNotExecuteWhenTheFeatureIsDisabled() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "未开通"))
                .when(permissions).require(USER, ResearchController.FEATURE_KEY);

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.run(1L)).getStatusCode());

        verify(service, never()).run(any(), any());
    }

    /**
     * 列表**不带**上下文与报告：一条上下文约 8KB，20 条就是 160KB，
     * 而列表页一个字节都用不上。
     */
    @Test
    void historyReturnsLightweightSummaries() {
        when(service.history(USER, 0, 20))
                .thenReturn(new PageImpl<>(List.of(task(ResearchTaskStatus.SUCCEEDED))));

        ApiResponse<Map<String, Object>> response = controller.history(0, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getData().get("items");

        assertEquals(1, items.size());
        assertEquals(1L, response.getData().get("total"), "total 是 Long，别拿 int 比");
        assertFalse(items.get(0).containsKey("context"), "列表不该带上下文");
        assertFalse(items.get(0).containsKey("report"), "列表不该带报告");
        assertTrue(items.get(0).containsKey("status"));
    }

    /** 详情把落库的 JSON 解析成对象，前端不必二次解析字符串。 */
    @Test
    void detailParsesTheStoredJsonIntoObjects() {
        ResearchTask task = task(ResearchTaskStatus.SUCCEEDED);
        task.setContextJson("{\"metrics\":{\"latest_close\":1725.5},\"warnings\":[]}");
        task.setReportJson("{\"summary\":\"偏乐观\",\"sections\":[{\"title\":\"走势\",\"content\":\"站上20日线\"}]}");
        when(service.get(USER, 1L)).thenReturn(task);

        Map<String, Object> data = controller.detail(1L).getData();

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) data.get("report");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) data.get("context");
        assertEquals("偏乐观", report.get("summary"));
        assertEquals(1725.5, ((Map<?, ?>) context.get("metrics")).get("latest_close"));
    }

    @Test
    void detailWithoutAReportYetReturnsNull() {
        when(service.get(USER, 1L)).thenReturn(task(ResearchTaskStatus.PENDING));

        Map<String, Object> data = controller.detail(1L).getData();

        assertEquals(null, data.get("report"));
        assertEquals(null, data.get("context"));
    }

    /** 报告确实存在时不能让前端显示空白，哪怕 JSON 被人工改坏了。 */
    @Test
    void detailFallsBackToTheRawStringWhenTheStoredJsonIsBroken() {
        ResearchTask task = task(ResearchTaskStatus.SUCCEEDED);
        task.setReportJson("{这不是合法 JSON");
        when(service.get(USER, 1L)).thenReturn(task);

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) controller.detail(1L).getData().get("report");

        assertEquals("{这不是合法 JSON", report.get("raw"));
    }

    /** 越权的 404 原样冒出去，不在这里被转成 200 或 403。 */
    @Test
    void anotherUsersTaskSurfacesAsNotFound() {
        when(service.get(USER, 1L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "研究任务不存在"));

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.detail(1L)).getStatusCode());
        verify(service, never()).history(any(), anyInt(), anyInt());
    }

    private static ResearchTask task(ResearchTaskStatus status) {
        return ResearchTask.builder()
                .id(1L)
                .userId(USER)
                .title("贵州茅台研究")
                .taskType(ResearchTaskType.REPORT)
                .market("a_share")
                .symbol("sh600519")
                .question("值得关注吗？")
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }
}