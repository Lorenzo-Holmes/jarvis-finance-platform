package com.jarvis.research.schedule;

/**
 * 一次执行落定后发出的事件（无论成败）。
 *
 * <p>为什么要有它：执行结果当前只落在 {@code scheduled_task_run} 与任务上的几个冗余字段里，
 * 用户不主动去看就永远不知道"任务失败了"。而"要不要把这次结果告诉用户"属于**通知**的职责，
 * 不是执行内核的职责 —— 内核只该把事实广播出去。</p>
 *
 * <p>与 {@link ScheduledTaskAutoPausedEvent} 的关系：那个只在"连续失败被自动暂停"时发，
 * 是**状态变更**；本事件每次执行都发，是**事实记录**。两者都由同一个地方（
 * {@link ScheduledTaskRunner#finish} 的事务提交后）发布，但消费方不同：
 * 前者让调度器把任务摘掉，后者让通知模块决定要不要提醒用户。</p>
 *
 * <p>⚠️ 与 {@code ScheduledTaskAutoPausedEvent} 同一个注意点：它是在事务**之外**发布的，
 * 监听方要么用 {@code @TransactionalEventListener(fallbackExecution = true)}，
 * 要么用普通 {@code @EventListener}；用不带 fallback 的事务监听器会**收不到**。</p>
 *
 * <p>刻意不把 {@code ScheduledTask} 实体整个塞进来：事件是跨模块的，
 * 传实体等于让消费方有机会改任务状态。这里只给只读的事实。</p>
 *
 * @param taskId        任务 id
 * @param userId        任务归属人（通知要发给谁）
 * @param taskName      任务名（用于拼通知标题，省一次查库）
 * @param taskType      任务类型（通知方据此判断要不要做类型专属处理，如风险命中）
 * @param status        本次执行终态
 * @param resultSummary 结果摘要（可能为 null）
 * @param errorMessage  失败原因（可能为 null）
 * @param artifactsJson 产物引用 JSON（风险类通知需要从里面取命中等级）
 */
public record ScheduledTaskRunFinishedEvent(Long taskId,
                                            Long userId,
                                            String taskName,
                                            ScheduledTaskType taskType,
                                            TaskRunStatus status,
                                            String resultSummary,
                                            String errorMessage,
                                            String artifactsJson) {
}
