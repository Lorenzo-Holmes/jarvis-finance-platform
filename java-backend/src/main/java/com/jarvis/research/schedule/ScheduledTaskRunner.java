package com.jarvis.research.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一次执行的编排：认领 → 派发 → 落状态。
 *
 * <p>这是"防重复执行"真正落地的地方，机制是 <strong>唯一键先插后跑</strong>：</p>
 *
 * <ol>
 *   <li>按 {@code <taskId>:<计划时刻>} 算幂等键；</li>
 *   <li>先往 {@code scheduled_task_run} 插一行 {@link TaskRunStatus#RUNNING} ——
 *       <strong>插得进去才继续执行</strong>；</li>
 *   <li>唯一约束冲突说明这个计划时刻已被处理过 → 直接跳过，不执行。</li>
 * </ol>
 *
 * <p>这样一次覆盖三种重复：同一时刻被触发多次、进程重启后按库重建调度把已跑过的
 * 时刻又算一遍、以及用户连点"立即执行"。之所以不依赖 {@code SELECT ... FOR UPDATE
 * SKIP LOCKED}：本地 H2（{@code MODE=MySQL}）不支持该语法，用它等于这套并发保护
 * <strong>在本机根本测不出来</strong>；而唯一约束在 H2 与 PostgreSQL 上行为一致。</p>
 *
 * <p>另一个刻意的边界：<strong>执行过程不包在事务里</strong>。
 * 回测是分钟级、报告生成要等大模型，把它们放进事务会让连接被长时间占用
 * （生产连接池只有 10 条）。所以只有"认领"和"落状态"两处用短事务。</p>
 */
@Slf4j
@Component
public class ScheduledTaskRunner {

    /** 与 {@code scheduled_task_run.result_summary} / {@code error_message} 的列宽一致。 */
    private static final int MAX_SUMMARY_LENGTH = 1000;
    private static final int MAX_ERROR_TYPE_LENGTH = 120;
    /** 与 {@code scheduled_task_run.artifacts_json} 的列宽一致。 */
    private static final int MAX_ARTIFACTS_LENGTH = 4000;

    private static final String DEFAULT_ZONE = "Asia/Shanghai";

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Map<ScheduledTaskType, ScheduledTaskExecutor> executors;
    private final int maxConsecutiveFailures;

    public ScheduledTaskRunner(ScheduledTaskRepository taskRepository,
                               ScheduledTaskRunRepository runRepository,
                               PlatformTransactionManager transactionManager,
                               ApplicationEventPublisher eventPublisher,
                               List<ScheduledTaskExecutor> executors,
                               @Value("${jarvis.scheduled-task.max-consecutive-failures:5}")
                               int maxConsecutiveFailures) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        // 同一类型注册两个执行器时这里会直接抛异常 —— 这是想要的效果：
        // 与其在运行时随机挑一个跑，不如启动即失败。
        this.executors = executors.stream()
                .collect(Collectors.toMap(ScheduledTaskExecutor::type, Function.identity()));
    }

    /**
     * 该类型当前是否有执行器认领。
     *
     * <p>存在的理由不是"内部检查"，而是让创建接口能<strong>在任务落库之前</strong>拒掉
     * 还没有执行器的类型。否则用户可以建出一个 {@code BACKTEST} 任务：它每次都失败、
     * 连续 5 次后被自动暂停，而用户完全不知道为什么。执行器一旦注册，
     * 这里自动返回 {@code true}，不需要改任何调用方代码。</p>
     */
    public boolean supports(ScheduledTaskType type) {
        return type != null && executors.containsKey(type);
    }

    /** 当前已就绪的任务类型，供前端把未开放的类型置灰。 */
    public Set<ScheduledTaskType> supportedTypes() {
        return Set.copyOf(executors.keySet());
    }

    /**
     * 触发一次执行。重复触发会被幂等地忽略（不抛异常）。
     *
     * @param task        任务定义
     * @param triggerType 触发来源
     * @param scheduledAt 计划触发时刻；它参与幂等键，所以**同一个计划时刻只会跑一次**
     */
    public void trigger(ScheduledTask task, TaskTriggerType triggerType, LocalDateTime scheduledAt) {
        String idempotencyKey = buildIdempotencyKey(task, scheduledAt, triggerType);

        Long runId = tryClaim(task, triggerType, scheduledAt, idempotencyKey);
        if (runId == null) {
            log.info("定时任务本次触发已被处理过，跳过。taskId={} key={}", task.getId(), idempotencyKey);
            return;
        }

        execute(runId, task);
    }

    /**
     * 认领这次执行。
     *
     * @return 新建的 run id；返回 {@code null} 表示幂等键冲突（已被处理过）
     */
    private Long tryClaim(ScheduledTask task, TaskTriggerType triggerType,
                          LocalDateTime scheduledAt, String idempotencyKey) {
        try {
            return transactionTemplate.execute(status -> runRepository.saveAndFlush(
                    ScheduledTaskRun.builder()
                            .taskId(task.getId())
                            .triggerType(triggerType)
                            .scheduledAt(scheduledAt)
                            .startedAt(LocalDateTime.now())
                            .status(TaskRunStatus.RUNNING)
                            .idempotencyKey(idempotencyKey)
                            .build()).getId());
        } catch (DataIntegrityViolationException conflict) {
            // 唯一约束 uk_task_run_idempotency 被撞 —— 这是正常的并发控制路径，不是错误。
            return null;
        }
    }

    /** 执行并落终态。任何异常都被吞进 run 记录，绝不向上抛（否则会打断调度线程）。 */
    private void execute(Long runId, ScheduledTask task) {
        LocalDateTime startedAt = LocalDateTime.now();
        ScheduledTaskExecutor executor = executors.get(task.getTaskType());

        if (executor == null) {
            String message = "没有执行器认领任务类型 " + task.getTaskType()
                    + "（已注册：" + executors.keySet() + "）";
            log.warn("{}. taskId={}", message, task.getId());
            finish(runId, task, TaskRunStatus.FAILED, null, null,
                    IllegalStateException.class.getName(), message, startedAt);
            return;
        }

        try {
            TaskExecutionResult result = executor.execute(task);
            finish(runId, task, TaskRunStatus.SUCCESS,
                    result == null ? null : result.summary(),
                    result == null ? null : result.artifactsJson(),
                    null, null, startedAt);
        } catch (Exception failure) {
            log.error("定时任务执行失败。taskId={} type={}", task.getId(), task.getTaskType(), failure);
            finish(runId, task, TaskRunStatus.FAILED, null, null,
                    failure.getClass().getName(), failure.getMessage(), startedAt);
        }
    }

    /**
     * 落终态：写 run 记录，并同步任务上那几个"冗余给列表页"的字段。
     *
     * <p>连续失败达阈值时把任务置为 {@link ScheduledTaskStatus#PAUSED}，
     * 并在事务提交后发事件让调度器把它摘掉（见 {@link ScheduledTaskAutoPausedEvent}）。</p>
     */
    private void finish(Long runId, ScheduledTask task, TaskRunStatus status,
                        String summary, String artifactsJson,
                        String errorType, String errorMessage,
                        LocalDateTime startedAt) {

        Boolean autoPaused = transactionTemplate.execute(tx -> {
            LocalDateTime finishedAt = LocalDateTime.now();

            runRepository.findById(runId).ifPresent(run -> {
                run.setStatus(status);
                run.setFinishedAt(finishedAt);
                run.setDurationMs(Duration.between(startedAt, finishedAt).toMillis());
                run.setResultSummary(truncate(summary, MAX_SUMMARY_LENGTH));
                run.setArtifactsJson(truncate(artifactsJson, MAX_ARTIFACTS_LENGTH));
                run.setErrorType(truncate(errorType, MAX_ERROR_TYPE_LENGTH));
                run.setErrorMessage(truncate(errorMessage, MAX_SUMMARY_LENGTH));
                runRepository.save(run);
            });

            ScheduledTask managed = taskRepository.findById(task.getId()).orElse(null);
            if (managed == null) {
                // 任务在本次执行期间被物理删除（正常路径是软删，但防御性地处理）。
                return false;
            }
            managed.setLastRunAt(finishedAt);
            managed.setLastRunStatus(status);

            if (status == TaskRunStatus.SUCCESS) {
                managed.setConsecutiveFailures(0);
                managed.setLastError(null);
            } else if (status == TaskRunStatus.FAILED || status == TaskRunStatus.TIMEOUT) {
                int failures = (managed.getConsecutiveFailures() == null ? 0 : managed.getConsecutiveFailures()) + 1;
                managed.setConsecutiveFailures(failures);
                managed.setLastError(truncate(errorMessage, MAX_SUMMARY_LENGTH));
                if (failures >= maxConsecutiveFailures) {
                    managed.setStatus(ScheduledTaskStatus.PAUSED);
                    managed.setNextRunAt(null);
                    taskRepository.save(managed);
                    return true;
                }
            }
            taskRepository.save(managed);
            return false;
        });

        if (Boolean.TRUE.equals(autoPaused)) {
            log.warn("定时任务连续失败 {} 次，已自动暂停。taskId={}", maxConsecutiveFailures, task.getId());
            eventPublisher.publishEvent(new ScheduledTaskAutoPausedEvent(
                    task.getId(), task.getUserId(), task.getName(), maxConsecutiveFailures));
        }

        // 无论成败都广播一次执行事实：要不要提醒用户属于通知模块的职责，内核只负责说清楚"发生了什么"。
        // 放在事务之外发布（与上面同一位置），所以监听方必须带 fallbackExecution = true。
        eventPublisher.publishEvent(new ScheduledTaskRunFinishedEvent(
                task.getId(), task.getUserId(), task.getName(), task.getTaskType(),
                status, summary, errorMessage, artifactsJson));
    }

    /**
     * 幂等键。
     *
     * <p>自动触发用「任务 + 计划时刻的秒」—— 这正是"同一个计划时刻只能跑一次"的表达。
     * 手动触发额外带 {@code manual} 前缀与**秒级**时间戳：既区别于自动触发，
     * 又让同一秒内的连点被合并掉（防抖），而不是把用户的手抖变成两次回测。</p>
     */
    private String buildIdempotencyKey(ScheduledTask task, LocalDateTime scheduledAt, TaskTriggerType triggerType) {
        ZoneId zone = resolveZone(task.getTimezone());
        if (triggerType == TaskTriggerType.MANUAL) {
            return task.getId() + ":manual:" + LocalDateTime.now().atZone(zone).toEpochSecond();
        }
        return task.getId() + ":" + scheduledAt.atZone(zone).toEpochSecond();
    }

    /** 时区解析失败一律回落到 {@code Asia/Shanghai}：宁可跑在默认时区，也不要因配置脏数据停摆。 */
    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            log.warn("无法识别的时区 {}，回落到 {}", timezone, DEFAULT_ZONE);
            return ZoneId.of(DEFAULT_ZONE);
        }
    }

    /** 截断到列宽以内：超长会直接让 INSERT 失败，而丢一点尾巴远好过整条记录写不进去。 */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
