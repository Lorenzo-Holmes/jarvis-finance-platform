package com.jarvis.research.schedule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 调度注册表：交给执行内核的必须是**计划时刻**。
 *
 * <p>这条断言不是形式主义 —— 幂等键由它算出，而它是"同一个计划时刻只跑一次"
 * 唯一的支点。若这里传的是 {@code LocalDateTime.now()}，触发一旦被线程池推迟
 * （3 个线程被分钟级回测占满是常态），键就会漂到另一个秒上：
 * 唯一约束不再拦住"同一计划时刻的第二处触发"，防重复机制静默失效。
 * 而失效的表现是偶发的、看不出原因的重复执行 —— 所以用一个纳秒位断言钉死它。</p>
 */
class ScheduledTaskRegistryTest {

    private final UserTaskSchedulerProvider provider = new UserTaskSchedulerProvider(1);

    @AfterEach
    void tearDown() {
        provider.shutdown();
    }

    @Test
    void scheduledTriggerHandsThePlannedInstantToTheRunnerNotTheWallClock() {
        ScheduledTaskRunner runner = mock(ScheduledTaskRunner.class);
        ScheduledTaskRegistry registry = new ScheduledTaskRegistry(
                provider,
                mock(ScheduledTaskRepository.class),
                mock(ScheduledTaskRunRepository.class),
                runner,
                30);

        ScheduledTask task = new ScheduledTask();
        task.setId(7L);
        task.setUserId(1L);
        task.setCronExpr("* * * * * *");
        task.setTimezone("Asia/Shanghai");
        task.setStatus(ScheduledTaskStatus.ACTIVE);

        assertTrue(registry.register(task), "ACTIVE 且 cron 合法时应注册成功");

        ArgumentCaptor<LocalDateTime> planned = ArgumentCaptor.forClass(LocalDateTime.class);
        // 每秒触发一次；用 timeout 等待而不是 sleep，避免机器忙时出现假失败。
        verify(runner, timeout(5000))
                .trigger(eq(task), eq(TaskTriggerType.SCHEDULED), planned.capture());

        LocalDateTime captured = planned.getValue();
        assertNotNull(captured);
        // cron 的计划时刻永远落在整秒上，而 now() 几乎不可能恰好整秒（纳秒位非零）。
        assertEquals(0, captured.getNano(),
                "传给内核的应是计划时刻（整秒），而不是 wall clock。实际: " + captured);
        // 它还得确实是「刚刚那一次」的计划时刻，而不是某个陈旧或离谱的值。
        long driftMillis = Math.abs(Duration.between(captured, LocalDateTime.now()).toMillis());
        assertTrue(driftMillis < 3_000,
                "计划时刻与当前时间相差过大（" + driftMillis + "ms），可能取错了值: " + captured);
    }
}