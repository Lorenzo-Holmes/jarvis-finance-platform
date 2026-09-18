package com.jarvis.research.schedule;

/**
 * 任务因连续失败而被自动暂停时发出的事件。
 *
 * <p>为什么用事件而不是让执行方直接调调度器：执行方（{@link ScheduledTaskRunner}）
 * 只该负责"跑完并记下来"，而"要不要把它从调度器里摘掉"是调度方
 * （{@link ScheduledTaskRegistry}）的职责。直接调用会形成双向依赖，
 * 也会让"暂停"这个动作散落在两处。</p>
 *
 * <p>⚠️ 监听方应当用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}：
 * 状态是在事务里改的，若事务回滚而调度已经取消，两边就不一致了。
 * （本事件实际是在事务**之外**发布的，所以监听方还需要带
 * {@code fallbackExecution = true}，否则收不到 —— 见 {@code ScheduledTaskRegistry.onAutoPaused}。）</p>
 *
 * <p>为什么要带 {@code userId} / {@code taskName}：通知模块（{@code ScheduledTaskNotificationListener}）
 * 需要它们来组织"发给谁、叫什么名字"。让每个消费方各自去查一次库，
 * 就是把一次广播变成 N 次查询，而且消费方还得处理"任务已被删掉"的分支。</p>
 *
 * @param taskId              被暂停的任务 id
 * @param userId              任务归属人
 * @param taskName            任务名
 * @param consecutiveFailures 触发暂停时的连续失败次数
 */
public record ScheduledTaskAutoPausedEvent(Long taskId,
                                           Long userId,
                                           String taskName,
                                           int consecutiveFailures) {
}
