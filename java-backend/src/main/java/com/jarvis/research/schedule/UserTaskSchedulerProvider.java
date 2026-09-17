package com.jarvis.research.schedule;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 动态任务专用的调度线程池。
 *
 * <p><strong>为什么用它而不是复用 {@code spring.task.scheduling} 那个池</strong>：
 * 那个池（{@code application.yml} 里 {@code pool.size=6}）上跑着 8 个常驻的秒级任务 ——
 * 行情采集 1s、积存金采集 1s、SSE 广播 1s、止损单触发 1s，其中好几个是网络 IO。
 * 用户自建任务里，回测是分钟级、报告生成要等大模型；一旦共用，
 * 慢任务会把 6 个线程占满，<strong>行情任务被饿死</strong> ——
 * 而行情是实时推送的源头，饿死的表现是整个页面价格卡住。</p>
 *
 * <p>⚠️ <strong>这里刻意不把它注册成 {@code @Bean}</strong>，这是一个必须踩过才知道的坑：
 * Spring Boot 的 {@code TaskSchedulingAutoConfiguration} 带有
 * {@code @ConditionalOnMissingBean(TaskScheduler.class)} —— 只要容器里出现任意一个
 * {@code TaskScheduler} bean，它<strong>就不再创建自己那个</strong>。
 * 而 {@code @EnableScheduling} 的处理器又会按类型去找 {@code TaskScheduler} bean，
 * 于是那 8 个静态 {@code @Scheduled} 会静默地全部跑到我们这个池上 ——
 * 恰好把"隔离"做成了"合并"。所以：自己持有实例、手动 {@code initialize()}，
 * 不放进容器。</p>
 *
 * <p>池大小默认 3 是刻意的保守值：用户任务多是 IO 等待（拉行情、调模型），
 * 但回测属 CPU 密集，开太大反而互相拖慢并挤占主业务。
 * 需要更高并发时调 {@code jarvis.scheduled-task.pool-size}，不必改代码。</p>
 */
@Component
public class UserTaskSchedulerProvider {

    /** 线程名前缀刻意区别于静态任务的 {@code jarvis-scheduler-}，线上 jstack 一眼能分清。 */
    private static final String THREAD_NAME_PREFIX = "jarvis-task-";

    private final ThreadPoolTaskScheduler scheduler;

    public UserTaskSchedulerProvider(@Value("${jarvis.scheduled-task.pool-size:3}") int poolSize) {
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(Math.max(1, poolSize));
        pool.setThreadNamePrefix(THREAD_NAME_PREFIX);

        // 停机时等在跑的任务收尾，避免把回测/报告生成腰斩在中间态。
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);

        // 取消时把任务从队列里摘掉：用户反复"暂停/改周期"时，
        // 不摘会让队列里堆积一堆永远不会执行的陈旧任务。
        pool.setRemoveOnCancelPolicy(true);

        pool.initialize();
        this.scheduler = pool;
    }

    /** 供调度注册表使用。 */
    public TaskScheduler scheduler() {
        return scheduler;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
