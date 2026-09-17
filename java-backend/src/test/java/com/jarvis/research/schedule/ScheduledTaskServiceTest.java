package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRUD 的行为断言。
 *
 * <p>重点钉三类容易被"能跑"掩盖的问题：</p>
 * <ul>
 *   <li><b>落库与调度必须成对</b>：暂停/删除只改库不摘调度，会出现"列表显示已暂停、
 *       实际还在按时触发"——这是最难查的那种不一致，所以每条写路径都断言内核被调用了。</li>
 *   <li><b>非法输入必须是 400 而不是 500</b>：cron 语法、触发频率、类型、参数形状、时区，
 *       责任方都是客户端。</li>
 *   <li><b>归属校验</b>：别人的任务、已删的任务，都只能看到 404。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduledTaskServiceTest {

    private static final long USER = 42L;
    private static final long TASK_ID = 7L;

    @Mock
    private ScheduledTaskRepository taskRepository;
    @Mock
    private ScheduledTaskRunRepository runRepository;
    @Mock
    private ScheduledTaskRegistry registry;
    @Mock
    private ScheduledTaskRunner runner;
    @Mock
    private UserTaskSchedulerProvider schedulerProvider;
    @Mock
    private TaskScheduler scheduler;

    private ScheduledTaskService service;

    @BeforeEach
    void setUp() {
        when(schedulerProvider.scheduler()).thenReturn(scheduler);
        when(runner.supports(any())).thenReturn(true);
        when(taskRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ScheduledTask task = invocation.getArgument(0);
            if (task == null) {
                // 重设桩时 Mockito 会先用占位参数调一次本方法，这里不能假定参数非空。
                return null;
            }
            if (task.getId() == null) {
                task.setId(TASK_ID);
            }
            if (task.getCreatedAt() == null) {
                task.setCreatedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now());
            }
            return task;
        });
        when(taskRepository.save(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == null ? null : invocation.getArgument(0));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(null);

        service = new ScheduledTaskService(taskRepository, runRepository, registry, runner,
                schedulerProvider, new ObjectMapper(), 20, 60);
    }

    // ------------------------------------------------------------------ 创建

    @Test
    void createPersistsAndImmediatelyRegistersTheTask() {
        ScheduledTask created = service.create(USER, request("每日行情扫描", "MARKET_SCAN", "0 0 9 * * *"));

        assertEquals(ScheduledTaskStatus.ACTIVE, created.getStatus());
        assertEquals(USER, created.getUserId());
        assertEquals("Asia/Shanghai", created.getTimezone());
        assertEquals(0, created.getConsecutiveFailures());
        // 创建即生效：只落库不注册，用户会看到任务"在列表里但永远不触发"。
        verify(registry).register(created);
    }

    @Test
    void createSerialisesParamsAsJson() {
        ScheduledTask created = service.create(USER,
                new ScheduledTaskService.TaskRequest("行情扫描", "MARKET_SCAN", "0 0 9 * * *",
                        null, Map.of("changeThresholdPct", 1.5)));

        assertTrue(created.getParamsJson().contains("changeThresholdPct"),
                "参数应当被序列化进 params_json，实际：" + created.getParamsJson());
    }

    @Test
    void createDefaultsParamsToAnEmptyObject() {
        ScheduledTask created = service.create(USER, request("行情扫描", "MARKET_SCAN", "0 0 9 * * *"));

        assertEquals("{}", created.getParamsJson());
    }

    @Test
    void createRejectsAnInvalidCronWith400() {
        ResponseStatusException e = assertBadRequest(
                () -> service.create(USER, request("坏 cron", "MARKET_SCAN", "0 0 9 * *")));

        assertTrue(e.getReason().contains("cron"), "文案要说清是 cron 的问题，实际：" + e.getReason());
    }

    @Test
    void createRejectsACronThatFiresMoreOftenThanTheFloor() {
        // 语法合法但每秒一次 —— 不挡的话能靠一个任务把调度池和数据库一起打满。
        ResponseStatusException e = assertBadRequest(
                () -> service.create(USER, request("每秒一次", "MARKET_SCAN", "* * * * * *")));

        assertTrue(e.getReason().contains("频繁"), "文案要说清是频率问题，实际：" + e.getReason());
    }

    @Test
    void createRejectsATypeWithoutAnExecutor() {
        when(runner.supports(ScheduledTaskType.BACKTEST)).thenReturn(false);

        ResponseStatusException e = assertBadRequest(
                () -> service.create(USER, request("回测", "BACKTEST", "0 0 9 * * *")));

        assertTrue(e.getReason().contains("尚未开放"),
                "要说清是执行器没实现，而不是让用户建一个每次都失败的任务，实际：" + e.getReason());
    }

    @Test
    void createRejectsAnUnknownType() {
        assertBadRequest(() -> service.create(USER, request("未知", "NO_SUCH_TYPE", "0 0 9 * * *")));
    }

    @Test
    void createRejectsADuplicateNameWith409() {
        when(taskRepository.existsByUserIdAndName(USER, "每日行情扫描")).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(USER, request("每日行情扫描", "MARKET_SCAN", "0 0 9 * * *")));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void createRejectsANonShanghaiTimezone() {
        ResponseStatusException e = assertBadRequest(() -> service.create(USER,
                new ScheduledTaskService.TaskRequest("纽约任务", "MARKET_SCAN", "0 0 9 * * *",
                        "America/New_York", null)));

        assertTrue(e.getReason().contains("Asia/Shanghai"), "实际：" + e.getReason());
    }

    @Test
    void createRejectsWhenThePerUserLimitIsReached() {
        when(taskRepository.countByUserIdAndStatusNot(USER, ScheduledTaskStatus.DELETED)).thenReturn(20L);

        ResponseStatusException e = assertBadRequest(
                () -> service.create(USER, request("第 21 个", "MARKET_SCAN", "0 0 9 * * *")));

        assertTrue(e.getReason().contains("上限"), "实际：" + e.getReason());
    }

    @Test
    void createRejectsParamsThatAreNotAJsonObject() {
        assertBadRequest(() -> service.create(USER,
                new ScheduledTaskService.TaskRequest("数组参数", "MARKET_SCAN", "0 0 9 * * *",
                        null, List.of(1, 2, 3))));
    }

    @Test
    void createTurnsAUniqueConstraintClashInto409() {
        when(taskRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_scheduled_task_user_name"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.create(USER, request("并发同名", "MARKET_SCAN", "0 0 9 * * *")));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    // ------------------------------------------------------------------ 暂停 / 恢复

    @Test
    void pauseClearsTheNextRunAndRemovesItFromTheSchedule() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(activeTask()));

        ScheduledTask paused = service.pause(USER, TASK_ID);

        assertEquals(ScheduledTaskStatus.PAUSED, paused.getStatus());
        assertNull(paused.getNextRunAt(), "暂停后不该还留着一个下次触发时刻，否则界面自相矛盾");
        verify(registry).cancel(TASK_ID);
    }

    @Test
    void pauseIsIdempotent() {
        ScheduledTask paused = activeTask();
        paused.setStatus(ScheduledTaskStatus.PAUSED);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(paused));

        // 已暂停的任务再点一次不该报错，也不该重复写库/重复调内核。
        assertEquals(ScheduledTaskStatus.PAUSED, service.pause(USER, TASK_ID).getStatus());
        verify(taskRepository, never()).save(any());
        verify(registry, never()).cancel(anyLong());
    }

    @Test
    void resumePutsItBackIntoTheSchedule() {
        ScheduledTask paused = activeTask();
        paused.setStatus(ScheduledTaskStatus.PAUSED);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(paused));

        ScheduledTask resumed = service.resume(USER, TASK_ID);

        assertEquals(ScheduledTaskStatus.ACTIVE, resumed.getStatus());
        verify(registry).register(resumed);
    }

    @Test
    void resumeRejectsACronThatWouldViolateTheFrequencyFloor() {
        // 库里的 cron 可能是旧版本写进去的，恢复时必须重新验一遍，否则会从"暂停的坏任务"
        // 变成"正在跑的坏任务"。
        ScheduledTask paused = activeTask();
        paused.setStatus(ScheduledTaskStatus.PAUSED);
        paused.setCronExpr("* * * * * *");
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(paused));

        assertBadRequest(() -> service.resume(USER, TASK_ID));
        verify(registry, never()).register(any());
    }

    // ------------------------------------------------------------------ 编辑

    @Test
    void updateReRegistersTheChangedSchedule() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(activeTask()));

        ScheduledTask updated = service.update(USER, TASK_ID,
                request("每日行情扫描", "MARKET_SCAN", "0 30 9 * * *"));

        assertEquals("0 30 9 * * *", updated.getCronExpr());
        // register 内部会先摘掉旧调度，所以这里断言的是"重新注册"而不是"又注册了一次"。
        verify(registry).register(updated);
    }

    @Test
    void updateKeepsAPausedTaskOutOfTheSchedule() {
        ScheduledTask paused = activeTask();
        paused.setStatus(ScheduledTaskStatus.PAUSED);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(paused));

        ScheduledTask updated = service.update(USER, TASK_ID,
                request("每日行情扫描", "MARKET_SCAN", "0 30 9 * * *"));

        assertEquals(ScheduledTaskStatus.PAUSED, updated.getStatus(), "编辑不该顺手把暂停的任务唤醒");
        assertNull(updated.getNextRunAt());
        verify(registry, never()).register(any());
    }

    @Test
    void updateResetsTheFailureCounter() {
        ScheduledTask failing = activeTask();
        failing.setConsecutiveFailures(4);
        failing.setLastError("上游超时");
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(failing));

        ScheduledTask updated = service.update(USER, TASK_ID,
                request("每日行情扫描", "MARKET_SCAN", "0 30 9 * * *"));

        assertEquals(0, updated.getConsecutiveFailures(),
                "改完配置还留着旧的失败计数，下一次失败就会直接撞上自动暂停阈值");
        assertNull(updated.getLastError());
    }

    @Test
    void updateCarriesAnOptimisticLockClashOutAs409() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(activeTask()));
        when(taskRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(ScheduledTask.class, TASK_ID));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.update(USER, TASK_ID, request("每日行情扫描", "MARKET_SCAN", "0 30 9 * * *")));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    // ------------------------------------------------------------------ 删除

    @Test
    void deleteSoftDeletesAndReleasesTheName() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(activeTask()));

        ScheduledTask deleted = service.delete(USER, TASK_ID);

        assertEquals(ScheduledTaskStatus.DELETED, deleted.getStatus());
        assertNull(deleted.getNextRunAt());
        // 唯一约束是 (user_id, name)，不改名的话这个名字会被一条已经看不见的任务永久占用。
        assertTrue(deleted.getName().contains("已删除"),
                "软删要把名称释放出来，实际：" + deleted.getName());
        assertTrue(deleted.getName().length() <= ScheduledTaskService.MAX_NAME);
        verify(registry).cancel(TASK_ID);
    }

    @Test
    void deleteWorksForAPausedTask() {
        ScheduledTask paused = activeTask();
        paused.setStatus(ScheduledTaskStatus.PAUSED);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(paused));

        assertEquals(ScheduledTaskStatus.DELETED, service.delete(USER, TASK_ID).getStatus());
    }

    // ------------------------------------------------------------------ 归属校验

    @Test
    void getTreatsAnotherUsersTaskAsMissing() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.get(USER, TASK_ID));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode(),
                "越权与不存在要返回同样的 404，否则等于告诉了攻击者哪条 id 是真的");
    }

    @Test
    void getTreatsASoftDeletedTaskAsMissing() {
        ScheduledTask deleted = activeTask();
        deleted.setStatus(ScheduledTaskStatus.DELETED);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(deleted));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.get(USER, TASK_ID));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void runsAreReadOnlyAfterOwnershipIsConfirmed() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(activeTask()));
        when(runRepository.findByTaskIdOrderByCreatedAtDesc(eq(TASK_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ScheduledTaskRun> history = service.runs(USER, TASK_ID, 0, 20);

        assertNotNull(history);
        verify(runRepository).findByTaskIdOrderByCreatedAtDesc(eq(TASK_ID), any(Pageable.class));
    }

    @Test
    void runsCannotBeReadForSomeoneElsesTask() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.runs(USER, TASK_ID, 0, 20));
        verify(runRepository, never()).findByTaskIdOrderByCreatedAtDesc(anyLong(), any(Pageable.class));
    }

    // ------------------------------------------------------------------ 列表

    @Test
    void listRejectsAnUnknownTypeFilter() {
        assertBadRequest(() -> service.list(USER, "NOT_A_TYPE", 0, 20));
    }

    @Test
    void listFiltersByTypeWhenAsked() {
        when(taskRepository.findByUserIdAndTaskTypeAndStatusNotOrderByCreatedAtDesc(
                eq(USER), eq(ScheduledTaskType.RISK_CHECK), eq(ScheduledTaskStatus.DELETED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(USER, "risk_check", 0, 20);

        verify(taskRepository).findByUserIdAndTaskTypeAndStatusNotOrderByCreatedAtDesc(
                eq(USER), eq(ScheduledTaskType.RISK_CHECK), eq(ScheduledTaskStatus.DELETED), any(Pageable.class));
    }

    @Test
    void listClampsThePageSize() {
        when(taskRepository.findByUserIdAndStatusNotOrderByCreatedAtDesc(
                eq(USER), eq(ScheduledTaskStatus.DELETED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(USER, null, -3, 5000);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findByUserIdAndStatusNotOrderByCreatedAtDesc(
                eq(USER), eq(ScheduledTaskStatus.DELETED), captor.capture());
        // 页大小不收敛的话，一个 size=5000 的请求就能把整张表连同每条执行的 JSON 拖出来。
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(ScheduledTaskService.MAX_LIST_PAGE_SIZE, captor.getValue().getPageSize());
    }

    // ------------------------------------------------------------------ 立即执行

    @Test
    void runNowDispatchesOnTheTaskPoolInsteadOfTheRequestThread() {
        ScheduledTask task = activeTask();
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(task));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        AtomicReference<Runnable> dispatched = new AtomicReference<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            dispatched.set(invocation.getArgument(0));
            return null;
        });

        service.runNow(USER, TASK_ID);

        // 关键：HTTP 线程只负责派发。回测是分钟级操作，在请求线程里同步跑会让前端长时间白屏，
        // 也容易被网关超时打断。
        verify(runner, never()).trigger(any(), any(), any());
        assertNotNull(dispatched.get(), "立即执行必须被派发到独立调度池上");

        dispatched.get().run();
        verify(runner).trigger(eq(task), eq(TaskTriggerType.MANUAL), any(LocalDateTime.class));
    }

    @Test
    void runNowIsAllowedOnAPausedTask() {
        ScheduledTask paused = activeTask();
        paused.setStatus(ScheduledTaskStatus.PAUSED);
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.of(paused));

        service.runNow(USER, TASK_ID);

        // 暂停的语义是"别自动跑"，不是"不许跑"。
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void runNowRejectsAnotherUsersTask() {
        when(taskRepository.findByIdAndUserId(TASK_ID, USER)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.runNow(USER, TASK_ID));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    // ------------------------------------------------------------------ 类型目录

    @Test
    void typeCatalogMarksTheTypesWithoutAnExecutor() {
        when(runner.supports(ScheduledTaskType.BACKTEST)).thenReturn(false);

        List<Map<String, Object>> catalog = service.typeCatalog();

        assertEquals(ScheduledTaskType.values().length, catalog.size());
        Map<String, Object> backtest = catalog.stream()
                .filter(item -> "BACKTEST".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertFalse((Boolean) backtest.get("supported"));
        assertTrue(catalog.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("supported"))));
    }

    // ------------------------------------------------------------------ 辅助

    private static ScheduledTaskService.TaskRequest request(String name, String type, String cron) {
        return new ScheduledTaskService.TaskRequest(name, type, cron, null, null);
    }

    private static ScheduledTask activeTask() {
        return ScheduledTask.builder()
                .id(TASK_ID)
                .userId(USER)
                .name("每日行情扫描")
                .taskType(ScheduledTaskType.MARKET_SCAN)
                .cronExpr("0 0 9 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson("{}")
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .version(0L)
                .build();
    }

    private static ResponseStatusException assertBadRequest(org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, action);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode(),
                "客户端输入问题应当是 400，实际：" + e.getStatusCode());
        return e;
    }
}
