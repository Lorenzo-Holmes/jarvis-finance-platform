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
 * 状态是在事务里改的，若事务回滚而调度已经取消，两边就不一致了。</p>
 *
 * @param taskId 被暂停的任务 id
 */
public record ScheduledTaskAutoPausedEvent(Long taskId) {
}
