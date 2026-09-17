package com.jarvis.research.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 调度注册表：把数据库里的任务定义变成真正会触发的调度。
 *
 * <p>这是"用户创建即生效、暂停即停止"得以成立的地方 —— 也是必须自建而非复用
 * {@code @Scheduled} 的原因：注解是编译期固定的，Spring 没有反注册它的 API。
 * 这里用 {@link TaskScheduler#schedule(Runnable, org.springframework.scheduling.Trigger)}
 * 编程式注册，返回的 {@link ScheduledFuture} 就是取消句柄。</p>
 *
 * <p><strong>以数据库为唯一事实来源</strong>：进程重启后内存里的注册全没了，
 * 所以启动时按 {@code status='ACTIVE'} 重建一遍。这也意味着"运行时改了 DB 但没调
 * {@link #register} / {@link #cancel}"会让两边不一致 —— 所以对外的入口只有这三个方法，
 * 不要让调用方直接改 repository。</p>
 */
@Slf4j
@Component
public class ScheduledTaskRegistry {

    private static final String DEFAULT_ZONE = "Asia/Shanghai";

    private final TaskScheduler scheduler;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunRepository runRepository;
    private final ScheduledTaskRunner runner;
    private final long zombieTimeoutMinutes;

    /** 任务 id → 取消句柄。只有 ACTIVE 的任务在这里。 */
    private final Map<Long, ScheduledFuture<?>> registered = new ConcurrentHashMap<>();

    public ScheduledTaskRegistry(UserTaskSchedulerProvider schedulerProvider,
                                 ScheduledTaskRepository taskRepository,
                                 ScheduledTaskRunRepository runRepository,
                                 ScheduledTaskRunner runner,
                                 @Value("${jarvis.scheduled-task.zombie-timeout-minutes:30}")
                                 long zombieTimeoutMinutes) {
        this.scheduler = schedulerProvider.scheduler();
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.runner = runner;
        this.zombieTimeoutMinutes = Math.max(1, zombieTimeoutMinutes);
    }

    /**
     * 启动时重建调度。
     *
     * <p>顺序有讲究：<strong>先清理僵尸执行记录，再重建注册</strong>。
     * 否则进程上次被强杀时留下的 {@code RUNNING} 记录会一直显示"运行中"，
     * 而用户看到的第一眼就是这个。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        int cleanedZombies = cleanupZombieRuns();
        List<ScheduledTask> active = taskRepository.findByStatus(ScheduledTaskStatus.ACTIVE);

        int registeredCount = 0;
        for (ScheduledTask task : active) {
            if (register(task)) {
                registeredCount++;
            }
        }

        log.info("定时任务调度重建完成：数据库 ACTIVE {} 个，注册成功 {} 个，清理僵尸执行 {} 条",
                active.size(), registeredCount, cleanedZombies);
    }

    /**
     * 注册（或重新注册）一个任务；调用方应为已持久化的 ACTIVE 任务。
     *
     * @return 注册成功返回 {@code true}；cron 非法等情况下返回 {@code false} 且不改动调度
     */
    public boolean register(ScheduledTask task) {
        if (task == null || task.getId() == null || !task.isRunnable()) {
            return false;
        }

        ZoneId zone = resolveZone(task.getTimezone());

        // 先用 CronExpression 校验一次：非法表达式必须在这里被挡住并留下明确日志，
        // 否则用户配错 cron 后，表现是"任务在列表里显示正常、但永远不会触发"。
        CronExpression expression;
        try {
            expression = CronExpression.parse(task.getCronExpr());
        } catch (IllegalArgumentException invalidCron) {
            log.error("cron 表达式非法，已跳过注册。taskId={} cron={}", task.getId(), task.getCronExpr(), invalidCron);
            return false;
        }

        // 先摘掉可能存在的旧调度，避免改周期后新旧两份同时触发。
        cancel(task.getId());

        CronTrigger cronTrigger = new CronTrigger(task.getCronExpr(), zone);

        // ⚠️ 传给内核的必须是**计划时刻**，不是 LocalDateTime.now()。
        // 幂等键是 <taskId>:<计划时刻 的 epochSecond>，「同一个计划时刻只跑一次」
        // 这句承诺只有在键取自计划时刻时才成立：取 now() 的话，触发一旦被推迟
        // （回测占满 3 个线程是常态，慢几秒很常见），键就漂到另一个秒上，
        // 于是「两处各自算出同一个键、唯一约束只放行一个」这个机制直接失效。
        // Trigger.nextExecution 给出的正是本次要触发的那个时刻，在这里记下来、
        // 等 Runnable 真正执行时取用。取用与重算在同一线程内先后发生（Spring 的
        // ReschedulingRunnable 是「跑完本次再算下次」），所以不存在竞态。
        AtomicReference<LocalDateTime> plannedInstant = new AtomicReference<>();
        Trigger plannedTrigger = context -> {
            Instant next = cronTrigger.nextExecution(context);
            if (next != null) {
                plannedInstant.set(LocalDateTime.ofInstant(next, zone));
            }
            return next;
        };

        ScheduledFuture<?> future = scheduler.schedule(
                () -> {
                    LocalDateTime planned = plannedInstant.get();
                    runner.trigger(task, TaskTriggerType.SCHEDULED,
                            planned != null ? planned : LocalDateTime.now());
                },
                plannedTrigger);
        registered.put(task.getId(), future);

        updateNextRunAt(task, expression, zone);
        return true;
    }

    /** 把任务移出调度。任务不存在于调度中也安全（幂等）。 */
    public void cancel(Long taskId) {
        if (taskId == null) {
            return;
        }
        ScheduledFuture<?> future = registered.remove(taskId);
        if (future != null) {
            // 不打断正在跑的那一次：本次让它跑完并落状态，取消只影响后续触发。
            future.cancel(false);
        }
    }

    /** 当前已注册的任务数，供运维页/健康检查观察调度是否活着。 */
    public int registeredCount() {
        return registered.size();
    }

    /**
     * 连续失败导致自动暂停后，把它从调度里摘掉。
     *
     * <p>{@code fallbackExecution = true} 是为了即使事件在无事务上下文里发布也能收到 ——
     * 拿不到事件的话，任务会一边显示"已暂停"一边继续按时触发，是最难查的那种不一致。</p>
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onAutoPaused(ScheduledTaskAutoPausedEvent event) {
        log.info("任务因连续失败被自动暂停，移出调度。taskId={}", event.taskId());
        cancel(event.taskId());
    }

    /**
     * 清理进程上次被强杀时留下的 {@code RUNNING} 执行记录。
     *
     * <p>只有 {@code RUNNING} 且开始时间早于阈值才动，避免误伤正在正常执行的慢任务
     * （回测可能跑十几分钟）。</p>
     */
    private int cleanupZombieRuns() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(zombieTimeoutMinutes);
        List<ScheduledTaskRun> zombies =
                runRepository.findByStatusAndStartedAtBefore(TaskRunStatus.RUNNING, threshold);
        if (zombies.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        for (ScheduledTaskRun zombie : zombies) {
            zombie.setStatus(TaskRunStatus.TIMEOUT);
            zombie.setFinishedAt(now);
            zombie.setErrorMessage("进程重启导致执行中断（超过 " + zombieTimeoutMinutes + " 分钟未结束）");
        }
        runRepository.saveAll(zombies);
        return zombies.size();
    }

    /** 把算出的下次触发时刻写回任务，供列表页展示。 */
    private void updateNextRunAt(ScheduledTask task, CronExpression expression, ZoneId zone) {
        try {
            task.setNextRunAt(expression.next(LocalDateTime.now(zone)));
            taskRepository.save(task);
        } catch (RuntimeException ignored) {
            // 只是展示用字段，算不出来不该影响调度本身。
            log.warn("计算下次触发时刻失败。taskId={}", task.getId());
        }
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of(DEFAULT_ZONE);
        }
    }
}
