package com.jarvis.research.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行编排的行为断言。
 *
 * <p>重点在三条容易被"看起来能跑"掩盖的路径：
 * 幂等键冲突时必须<strong>跳过而不是执行</strong>；
 * 执行器抛异常时必须落成 FAILED 并带上异常类型（否则排障时只有一句"失败了"）；
 * 连续失败到阈值必须自动暂停并发出事件（否则坏任务会一直按时重试下去）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskRunnerTest {

    private static final long TASK_ID = 1L;

    @Mock
    private ScheduledTaskRepository taskRepository;
    @Mock
    private ScheduledTaskRunRepository runRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final AtomicBoolean shouldFail = new AtomicBoolean(false);
    private final ScheduledTaskExecutor executor = new ScheduledTaskExecutor() {
        @Override
        public ScheduledTaskType type() {
            return ScheduledTaskType.MARKET_SCAN;
        }

        @Override
        public TaskExecutionResult execute(ScheduledTask task) {
            if (shouldFail.get()) {
                throw new IllegalStateException("上游行情源超时");
            }
            return TaskExecutionResult.of("扫描完成：2 个标的正常");
        }
    };

    private ScheduledTaskRunner runner;
    private ScheduledTask task;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        task = ScheduledTask.builder()
                .id(TASK_ID)
                .userId(2L)
                .name("每日行情扫描")
                .taskType(ScheduledTaskType.MARKET_SCAN)
                .cronExpr("0 0 9 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson("{}")
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .build();

        runner = new ScheduledTaskRunner(taskRepository, runRepository, txManager,
                eventPublisher, List.of(executor), 3);
    }

    @Test
    void successfulRunIsRecordedAndResetsTheFailureCounter() {
        when(runRepository.saveAndFlush(any())).thenReturn(claimedRun(TaskRunStatus.RUNNING));
        when(runRepository.findById(100L)).thenReturn(Optional.of(claimedRun(TaskRunStatus.RUNNING)));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        runner.trigger(task, TaskTriggerType.SCHEDULED, LocalDateTime.now());

        assertEquals(TaskRunStatus.SUCCESS, task.getLastRunStatus());
        assertEquals(0, task.getConsecutiveFailures());
        assertNull(task.getLastError());
        verify(eventPublisher, never()).publishEvent(any(ScheduledTaskAutoPausedEvent.class));
    }

    @Test
    void conflictingIdempotencyKeySkipsExecutionInsteadOfRunningAgain() {
        // 唯一约束被撞 —— 说明这个计划时刻已经被认领过了。
        when(runRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_task_run_idempotency"));

        runner.trigger(task, TaskTriggerType.SCHEDULED, LocalDateTime.now());

        // 关键断言：既不能再写终态（那是别人那次执行的行），也不能改任务状态。
        verify(runRepository, never()).findById(any());
        verify(taskRepository, never()).findById(any());
        assertNull(task.getLastRunStatus(), "跳过的一次不应污染任务的最近执行状态");
    }

    @Test
    void failureIsRecordedWithExceptionTypeSoTroubleshootingHasSomethingToGoOn() {
        shouldFail.set(true);
        when(runRepository.saveAndFlush(any())).thenReturn(claimedRun(TaskRunStatus.RUNNING));
        when(runRepository.findById(100L)).thenReturn(Optional.of(claimedRun(TaskRunStatus.RUNNING)));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        runner.trigger(task, TaskTriggerType.SCHEDULED, LocalDateTime.now());

        assertEquals(TaskRunStatus.FAILED, task.getLastRunStatus());
        assertEquals(1, task.getConsecutiveFailures());
        assertTrue(task.getLastError().contains("上游行情源超时"),
                "错误摘要要能看出发生了什么，实际：" + task.getLastError());
    }

    @Test
    void reachingTheFailureThresholdAutoPausesAndNotifiesTheScheduler() {
        shouldFail.set(true);
        task.setConsecutiveFailures(2); // 阈值是 3，这次失败后就到线
        when(runRepository.saveAndFlush(any())).thenReturn(claimedRun(TaskRunStatus.RUNNING));
        when(runRepository.findById(100L)).thenReturn(Optional.of(claimedRun(TaskRunStatus.RUNNING)));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        runner.trigger(task, TaskTriggerType.SCHEDULED, LocalDateTime.now());

        assertEquals(ScheduledTaskStatus.PAUSED, task.getStatus());
        assertNull(task.getNextRunAt(), "暂停后不该再显示下次执行时间");
        // 必须发出事件：否则任务显示"已暂停"却仍在后台按时触发，是最难查的不一致。
        verify(eventPublisher).publishEvent(new ScheduledTaskAutoPausedEvent(TASK_ID));
    }

    @Test
    void missingExecutorIsReportedAsFailureRatherThanSilentlyDoingNothing() {
        ScheduledTaskRunner runnerWithoutExecutors = new ScheduledTaskRunner(
                taskRepository, runRepository, mockTransactionManager(), eventPublisher, List.of(), 3);

        when(runRepository.saveAndFlush(any())).thenReturn(claimedRun(TaskRunStatus.RUNNING));
        when(runRepository.findById(100L)).thenReturn(Optional.of(claimedRun(TaskRunStatus.RUNNING)));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

        runnerWithoutExecutors.trigger(task, TaskTriggerType.SCHEDULED, LocalDateTime.now());

        assertEquals(TaskRunStatus.FAILED, task.getLastRunStatus());
        assertTrue(task.getLastError().contains("没有执行器认领"),
                "应明确指出是「没有执行器」而不是笼统的失败，实际：" + task.getLastError());
    }

    private static PlatformTransactionManager mockTransactionManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return txManager;
    }

    private static ScheduledTaskRun claimedRun(TaskRunStatus status) {
        return ScheduledTaskRun.builder()
                .id(100L)
                .taskId(TASK_ID)
                .triggerType(TaskTriggerType.SCHEDULED)
                .scheduledAt(LocalDateTime.now())
                .startedAt(LocalDateTime.now())
                .status(status)
                .idempotencyKey("1:1789606081")
                .build();
    }
}
