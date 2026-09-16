package com.jarvis.research.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResearchTaskService} 的编排行为。
 *
 * <p>三个 mock（仓储、上下文构建器、AI 代理）就是这条链上的全部外部依赖，
 * 所以这里可以在毫秒级把"成功 / AI 失败 / 配额拒绝 / 越权 / 并发重入"全跑一遍，
 * 不需要容器、不需要网络。</p>
 */
class ResearchTaskServiceTest {

    private static final long USER = 42L;

    private ResearchTaskRepository repository;
    private ResearchContextBuilder contextBuilder;
    private AiProxyService aiProxyService;
    private AiRateLimitService aiRateLimitService;
    private ResearchTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResearchTaskRepository.class);
        contextBuilder = mock(ResearchContextBuilder.class);
        aiProxyService = mock(AiProxyService.class);
        aiRateLimitService = mock(AiRateLimitService.class);
        // findAndRegisterModules：上下文里有 LocalDateTime，裸 ObjectMapper 会序列化失败
        service = new ResearchTaskService(repository, contextBuilder, aiProxyService,
                aiRateLimitService, new ObjectMapper().findAndRegisterModules());
        when(repository.save(any(ResearchTask.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ==================== 创建 ====================

    @Test
    void createStoresAPendingTaskWithTheTrimmedRequest() {
        ResearchTask task = service.create(USER, new ResearchTaskService.NewTask(
                "  贵州茅台研究  ", ResearchTaskType.RISK, " a_share ", " sh600519 ", " 值得关注吗？ "));

        assertEquals(USER, task.getUserId());
        assertEquals("贵州茅台研究", task.getTitle());
        assertEquals(ResearchTaskType.RISK, task.getTaskType());
        assertEquals("a_share", task.getMarket());
        assertEquals("sh600519", task.getSymbol());
        assertEquals("值得关注吗？", task.getQuestion());
        assertEquals(ResearchTaskStatus.PENDING, task.getStatus(), "新建任务不该是 RUNNING 或 SUCCEEDED");
        assertNotNull(task.getCreatedAt());
        verify(repository).save(any(ResearchTask.class));
    }

    @Test
    void createDefaultsTheTaskTypeToReport() {
        ResearchTask task = service.create(USER, new ResearchTaskService.NewTask(
                null, null, null, "sh600519", null));

        assertEquals(ResearchTaskType.REPORT, task.getTaskType());
        assertNull(task.getTitle());
        assertNull(task.getQuestion());
    }

    @Test
    void createRejectsATaskThatAsksNothingAboutNothing() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(USER, new ResearchTaskService.NewTask(null, null, null, null, "   ")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(repository, never()).save(any(ResearchTask.class));
    }

    @Test
    void createAcceptsASymbolWithoutAQuestion() {
        // 只要问题或标的有一个就成立
        assertNotNull(service.create(USER, new ResearchTaskService.NewTask(
                null, null, null, "sh600519", null)));
    }

    /**
     * 超长字段要在入库前拦成 400。
     *
     * <p>让数据库抛 "value too long for column" 是 500，责任方就变成了服务端；
     * 而这明明是客户端给错了数据。两者的责任方不同，不能混。</p>
     */
    @Test
    void createRejectsOverlongFieldsBeforeTouchingTheDatabase() {
        assertBadRequest(new ResearchTaskService.NewTask("x".repeat(201), null, null, "s", null), "标题");
        assertBadRequest(new ResearchTaskService.NewTask(null, null, null, "s", "x".repeat(2001)), "问题");
        assertBadRequest(new ResearchTaskService.NewTask(null, null, null, "x".repeat(33), null), "标的");
        assertBadRequest(new ResearchTaskService.NewTask(null, null, "x".repeat(21), "s", null), "市场");
    }

    @Test
    void createAcceptsFieldsExactlyAtTheLimit() {
        ResearchTask task = service.create(USER, new ResearchTaskService.NewTask(
                "x".repeat(200), null, "x".repeat(20), "x".repeat(32), "x".repeat(2000)));

        assertEquals(200, task.getTitle().length());
        assertEquals(2000, task.getQuestion().length());
    }

    // ==================== 执行：成功路径 ====================

    @Test
    void runBuildsContextCallsAiAndStoresEverything() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(eq(ResearchTaskService.AI_REPORT_PATH), any()))
                .thenReturn(envelope(Map.of(
                        "summary", "偏乐观",
                        "sections", List.of(Map.of("title", "走势", "content", "站上20日线")),
                        "risks", List.of("成交量偏低"),
                        "model", "deepseek-x",
                        "usage", Map.of("prompt_tokens", 900, "completion_tokens", 300))));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskStatus.SUCCEEDED, result.getStatus());
        assertNotNull(result.getStartedAt());
        assertNotNull(result.getFinishedAt());
        assertNull(result.getErrorMessage());
        assertEquals("deepseek-x", result.getModel());
        assertEquals(900, result.getPromptTokens());
        assertEquals(300, result.getCompletionTokens());
        assertTrue(result.getReportJson().contains("偏乐观"), "报告要落库");
        assertTrue(result.getContextJson().contains("latest_close"), "上下文要落库");
    }

    /** 传给 Python 的必须是 Java 算好的指标，而不是原始K线让它自己算。 */
    @Test
    void runSendsTheJavaComputedMetricsAndWarningsToTheAiService() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any())).thenReturn(envelope(Map.of("summary", "s")));

        service.run(USER, 1L);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(aiProxyService).post(eq(ResearchTaskService.AI_REPORT_PATH), bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();

        assertEquals("REPORT", body.get("task_type"));
        assertEquals("a_share", body.get("market"));
        assertEquals("sh600519", body.get("symbol"));
        assertEquals(Map.of("latest_close", 1725.5), body.get("metrics"));
        assertEquals(List.of("未取到最新报价"), body.get("warnings"));
        assertFalse(body.containsKey("klines"), "不该把原始K线交给模型去算");
        assertFalse(body.containsKey("closes"));
    }

    @Test
    void runConsumesQuotaAndRecordsTheReturnedTokens() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        Map<String, Object> response = envelope(Map.of("summary", "s",
                "usage", Map.of("total_tokens", 1200)));
        when(aiProxyService.post(any(), any())).thenReturn(response);

        service.run(USER, 1L);

        verify(aiRateLimitService).consume(USER);
        verify(aiRateLimitService).recordTokens(USER, response);
    }

    /** 配额被拒是这次请求被拒，**不是任务失败**——状态必须保持原样。 */
    @Test
    void runDoesNotMarkTheTaskFailedWhenTheQuotaRefusesTheRequest() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求过于频繁"))
                .when(aiRateLimitService).consume(USER);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.run(USER, 1L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
        assertEquals(ResearchTaskStatus.PENDING, task.getStatus(), "被限流不该改成 FAILED");
        assertNull(task.getErrorMessage());
        verify(aiProxyService, never()).post(any(), any());
    }

    // ==================== 执行：失败路径 ====================

    /**
     * AI 失败 → FAILED + 原因落库 + **上下文保留**。
     *
     * <p>上下文保留是关键：它是排查"数据没取到还是模型没答好"的唯一依据。</p>
     */
    @Test
    void runRecordsAFailureWithTheReasonAndKeepsTheContext() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any()))
                .thenThrow(new IllegalStateException("AI 上游连接失败"));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskStatus.FAILED, result.getStatus());
        assertEquals("AI 上游连接失败", result.getErrorMessage());
        assertNotNull(result.getFinishedAt());
        assertNotNull(result.getContextJson(), "失败也要留下上下文");
        assertNull(result.getReportJson());
    }

    /** 嵌套异常要取根因：WebClient 的外层文字会掩盖真正的原因。 */
    @Test
    void runReportsTheRootCauseNotTheOuterWrapper() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any())).thenThrow(
                new RuntimeException("wrapper", new java.net.ConnectException("Connection refused")));

        ResearchTask result = service.run(USER, 1L);

        assertEquals("Connection refused", result.getErrorMessage());
    }

    /**
     * 超长异常消息必须截断——error_message 只有 1000 字符，
     * 直接落库会让"记录失败"这件事本身也失败，于是任务卡在 RUNNING。
     */
    @Test
    void runTruncatesAnOverlongErrorMessage() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any())).thenThrow(new IllegalStateException("x".repeat(5000)));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskService.MAX_ERROR_MESSAGE, result.getErrorMessage().length());
        assertEquals(ResearchTaskStatus.FAILED, result.getStatus());
    }

    /** code!=200 的信封里没有报告，不能当成报告存进 report_json。 */
    @Test
    void runTreatsANonSuccessEnvelopeAsAFailure() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any()))
                .thenReturn(Map.of("code", 500, "message", "AI 上游调用失败"));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("AI 上游调用失败"), result.getErrorMessage());
        assertNull(result.getReportJson());
    }

    @Test
    void runTreatsAMissingDataBlockAsAFailure() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any())).thenReturn(Map.of("code", 200, "message", "ok"));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("data"), result.getErrorMessage());
    }

    /** 没取到行情也要照常问模型：报告里会如实说明数据不足。 */
    @Test
    void runStillAsksTheModelWhenNoMarketDataWasAvailable() {
        ResearchTask task = pendingTask();
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(new ResearchContext(
                "a_share", "sh600519", "1d", LocalDateTime.now(), 0, null, null,
                null, Map.of(), List.of("未取到日K数据，无法计算技术指标")));
        when(aiProxyService.post(any(), any())).thenReturn(envelope(Map.of("summary", "数据不足")));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskStatus.SUCCEEDED, result.getStatus());
        assertTrue(result.getContextJson().contains("未取到日K数据"));
    }

    // ==================== 并发重入 / 越权 ====================

    @Test
    void runRefusesATaskThatIsAlreadyRunning() {
        ResearchTask task = pendingTask();
        task.setStatus(ResearchTaskStatus.RUNNING);
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.run(USER, 1L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(aiProxyService, never()).post(any(), any());
        verify(aiRateLimitService, never()).consume(anyLong());
    }

    /** 别人任务的存在性不该被泄漏：不存在与不属于你都返回 404。 */
    @Test
    void getReturns404ForAnotherUsersTask() {
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.get(USER, 1L)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.run(USER, 1L)).getStatusCode());
    }

    /** 失败过的任务可以重跑，重跑要清掉上一次的错误与结束时间。 */
    @Test
    void aFailedTaskCanBeRerunAndSucceeds() {
        ResearchTask task = pendingTask();
        task.setStatus(ResearchTaskStatus.FAILED);
        task.setErrorMessage("上次失败了");
        task.setFinishedAt(LocalDateTime.now().minusHours(1));
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task));
        when(contextBuilder.build(any())).thenReturn(context());
        when(aiProxyService.post(any(), any())).thenReturn(envelope(Map.of("summary", "这次成功了")));

        ResearchTask result = service.run(USER, 1L);

        assertEquals(ResearchTaskStatus.SUCCEEDED, result.getStatus());
        assertNull(result.getErrorMessage(), "重跑成功要清掉上次的错误");
        verify(aiRateLimitService).consume(USER);
    }

    // ==================== 历史 ====================

    @Test
    void historyIsScopedToTheUserAndClampsThePageSize() {
        Page<ResearchTask> page = new PageImpl<>(List.of(pendingTask()));
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(USER), any(Pageable.class)))
                .thenReturn(page);

        service.history(USER, -3, 5000);
        service.history(USER, 0, 0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, org.mockito.Mockito.times(2))
                .findByUserIdOrderByCreatedAtDesc(eq(USER), captor.capture());
        List<Pageable> pages = captor.getAllValues();
        assertEquals(0, pages.get(0).getPageNumber(), "负页码收敛到 0");
        assertEquals(ResearchTaskService.MAX_HISTORY_PAGE_SIZE, pages.get(0).getPageSize(),
                "页大小要有上限，否则一次请求能把整库拉出来");
        assertEquals(1, pages.get(1).getPageSize(), "0 收敛到 1");
    }

    // ==================== 夹具 ====================

    private static ResearchTask pendingTask() {
        return ResearchTask.builder()
                .id(1L)
                .userId(USER)
                .title("贵州茅台研究")
                .taskType(ResearchTaskType.REPORT)
                .market("a_share")
                .symbol("sh600519")
                .question("值得关注吗？")
                .status(ResearchTaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static ResearchContext context() {
        return new ResearchContext("a_share", "sh600519", "1d", LocalDateTime.now(), 120,
                "2026-03-01", "2026-09-16", Map.of("price", 1725.5),
                Map.of("latest_close", 1725.5), List.of("未取到最新报价"));
    }

    private static Map<String, Object> envelope(Map<String, Object> data) {
        return Map.of("code", 200, "message", "ok", "data", data);
    }

    private void assertBadRequest(ResearchTaskService.NewTask request, String labelFragment) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.create(USER, request), labelFragment + " 超长应当被拦截");
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains(labelFragment));
    }
}