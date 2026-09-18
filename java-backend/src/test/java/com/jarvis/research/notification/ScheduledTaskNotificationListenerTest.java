package com.jarvis.research.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.schedule.ScheduledTaskAutoPausedEvent;
import com.jarvis.research.schedule.ScheduledTaskRunFinishedEvent;
import com.jarvis.research.schedule.ScheduledTaskType;
import com.jarvis.research.schedule.TaskRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知策略的断言 —— 也就是"什么该提醒、什么不该提醒"。
 *
 * <p>这是整条通知链路里唯一有产品判断的地方，所以逐条钉住：</p>
 * <ul>
 *   <li>失败与超时必须提醒（否则用户不知道任务挂了）；</li>
 *   <li>风险检测命中时，等级必须**跟着风控引擎的 riskStatus 走**（DANGER → RISK、WARN → WARN）——
 *       一律用最高级会让"接近强平"和"刚越警戒线"看起来一样，等于没有优先级；</li>
 *   <li>成功**不**提醒，风险检测未命中也不提醒 —— 否则每天都在报"一切正常"，
 *       真正重要的那条会被淹掉；</li>
 *   <li>通知写入失败必须被吞掉，绝不能把异常抛回任务执行链路。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduledTaskNotificationListenerTest {

    private static final long TASK_ID = 9L;
    private static final long USER = 7L;

    @Mock
    private NotificationService notificationService;

    private ScheduledTaskNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new ScheduledTaskNotificationListener(notificationService, new ObjectMapper());
    }

    // ------------------------------------------------------------------ 失败类

    @Test
    void aFailedRunRaisesAWarningWithTheReason() {
        listener.onRunFinished(runFinished(TaskRunStatus.FAILED, ScheduledTaskType.MARKET_SCAN,
                null, "上游行情源超时", null));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).raise(eq(USER), eq(NotificationType.TASK_FAILED), eq(NotificationLevel.WARN),
                title.capture(), body.capture(), eq("SCHEDULED_TASK"), eq(TASK_ID));

        assertTrue(title.getValue().contains("每日行情扫描"), "标题要带任务名，实际：" + title.getValue());
        assertTrue(title.getValue().contains("失败"));
        assertTrue(body.getValue().contains("上游行情源超时"), "原因要进正文，实际：" + body.getValue());
    }

    @Test
    void aTimeoutIsReportedAsATimeoutRatherThanAGenericFailure() {
        listener.onRunFinished(runFinished(TaskRunStatus.TIMEOUT, ScheduledTaskType.BACKTEST,
                null, null, null));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService).raise(eq(USER), eq(NotificationType.TASK_FAILED), eq(NotificationLevel.WARN),
                title.capture(), any(), eq("SCHEDULED_TASK"), eq(TASK_ID));
        assertTrue(title.getValue().contains("超时"),
                "超时与失败的处理动作不同（一个要调参、一个要排查上游），文案必须分开，实际：" + title.getValue());
    }

    @Test
    void aFailureWithoutAnExceptionFallsBackToTheSummary() {
        listener.onRunFinished(runFinished(TaskRunStatus.FAILED, ScheduledTaskType.MARKET_SCAN,
                "扫描到一半被中断", null, null));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).raise(eq(USER), any(), any(), any(), body.capture(), any(), any());
        assertTrue(body.getValue().contains("扫描到一半被中断"));
    }

    @Test
    void autoPauseIsReportedBecauseTheTaskWillNeverRunAgainOnItsOwn() {
        listener.onAutoPaused(new ScheduledTaskAutoPausedEvent(TASK_ID, USER, "每日回测", 5));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).raise(eq(USER), eq(NotificationType.TASK_AUTO_PAUSED), eq(NotificationLevel.WARN),
                any(), body.capture(), eq("SCHEDULED_TASK"), eq(TASK_ID));
        assertTrue(body.getValue().contains("5"), "要写清连续失败了几次，实际：" + body.getValue());
    }

    // ------------------------------------------------------------------ 风险命中

    @Test
    void aDangerousRiskCheckRaisesTheTopLevelAlert() {
        listener.onRunFinished(runFinished(TaskRunStatus.SUCCESS, ScheduledTaskType.RISK_CHECK,
                "风险检测命中：维持担保比例 12.50%（风险等级 DANGER）", null,
                "{\"riskStatus\":\"DANGER\",\"maintMarginPct\":12.5}"));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService).raise(eq(USER), eq(NotificationType.RISK_ALERT), eq(NotificationLevel.RISK),
                title.capture(), any(), eq("SCHEDULED_TASK"), eq(TASK_ID));
        assertTrue(title.getValue().contains("12.50%"), "标题要直接给出关键数字，实际：" + title.getValue());
    }

    @Test
    void aWarnLevelRiskCheckIsOnlyAWarning() {
        listener.onRunFinished(runFinished(TaskRunStatus.SUCCESS, ScheduledTaskType.RISK_CHECK,
                "风险检测命中：维持担保比例 20.00%", null,
                "{\"riskStatus\":\"WARN\",\"maintMarginPct\":20.0}"));

        verify(notificationService).raise(eq(USER), eq(NotificationType.RISK_ALERT), eq(NotificationLevel.WARN),
                any(), any(), eq("SCHEDULED_TASK"), eq(TASK_ID));
    }

    @Test
    void aHealthyRiskCheckRaisesNothing() {
        listener.onRunFinished(runFinished(TaskRunStatus.SUCCESS, ScheduledTaskType.RISK_CHECK,
                "风险检测通过：维持担保比例 320.15%", null,
                "{\"riskStatus\":\"SAFE\",\"maintMarginPct\":320.15}"));

        verify(notificationService, never()).raise(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRiskCheckWithoutArtifactsRaisesNothing() {
        // 产物缺失时宁可不提醒，也不要凭"执行成功"猜一个风险等级出来。
        listener.onRunFinished(runFinished(TaskRunStatus.SUCCESS, ScheduledTaskType.RISK_CHECK,
                "风险检测完成", null, null));

        verify(notificationService, never()).raise(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unparsableArtifactsAreSkippedInsteadOfCrashing() {
        assertDoesNotThrow(() -> listener.onRunFinished(runFinished(TaskRunStatus.SUCCESS,
                ScheduledTaskType.RISK_CHECK, "风险检测完成", null, "{不是 JSON")));

        verify(notificationService, never()).raise(any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 不该通知的

    @Test
    void aSuccessfulRunOfAnOrdinaryTaskRaisesNothing() {
        listener.onRunFinished(runFinished(TaskRunStatus.SUCCESS, ScheduledTaskType.MARKET_SCAN,
                "行情扫描完成：2 个标的正常", null, null));

        verify(notificationService, never()).raise(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aManualRunIsNotTreatedDifferently() {
        // 当前策略不看触发来源：手动触发的失败同样值得记一条（用户可能已经离开页面）。
        listener.onRunFinished(runFinished(TaskRunStatus.FAILED, ScheduledTaskType.MARKET_SCAN,
                null, "上游超时", null));

        verify(notificationService).raise(any(), eq(NotificationType.TASK_FAILED), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 健壮性

    @Test
    void aNotificationFailureNeverPropagatesBackIntoTheTaskPipeline() {
        when(notificationService.raise(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("通知表写不进去"));

        assertDoesNotThrow(() -> listener.onRunFinished(runFinished(TaskRunStatus.FAILED,
                ScheduledTaskType.MARKET_SCAN, null, "上游超时", null)),
                "通知是锦上添花，绝不能把异常抛回任务执行链路");
        assertDoesNotThrow(() -> listener.onAutoPaused(
                new ScheduledTaskAutoPausedEvent(TASK_ID, USER, "任务", 5)));
    }

    @Test
    void eventsWithoutAUserIdOrWithoutAnEventAreIgnored() {
        // userId 为空说明任务归属不明 —— 这种通知没有收件人，直接跳过而不是抛异常。
        listener.onRunFinished(new ScheduledTaskRunFinishedEvent(TASK_ID, null, "任务",
                ScheduledTaskType.MARKET_SCAN, TaskRunStatus.FAILED, null, "x", null));
        listener.onRunFinished(null);
        listener.onAutoPaused(new ScheduledTaskAutoPausedEvent(TASK_ID, null, "任务", 5));
        listener.onAutoPaused(null);

        verify(notificationService, never()).raise(any(), any(), any(), any(), any(), any(), any());
    }

    private static ScheduledTaskRunFinishedEvent runFinished(TaskRunStatus status, ScheduledTaskType type,
                                                             String summary, String error, String artifacts) {
        return new ScheduledTaskRunFinishedEvent(TASK_ID, USER, "每日行情扫描", type, status,
                summary, error, artifacts);
    }
}
