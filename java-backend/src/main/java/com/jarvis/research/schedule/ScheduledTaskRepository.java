package com.jarvis.research.schedule;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 定时任务仓储。
 *
 * <p>查询一律**带 userId**：任务定义里含用户自己的标的与阈值配置，属私人内容。
 * 这里不提供除继承来的 {@code findById} 之外的无主查询，只给
 * {@link #findByIdAndUserId} —— 越权在这一层就做不到，而不是指望每个调用点都记得校验。
 * （与 {@code ResearchTaskRepository} 同一个约定。）</p>
 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    /** 任务列表：默认排除软删的记录，最新在前。 */
    Page<ScheduledTask> findByUserIdAndStatusNotOrderByCreatedAtDesc(
            Long userId, ScheduledTaskStatus status, Pageable pageable);

    /**
     * 按类型筛选的任务列表。
     *
     * <p>「风险中心 &gt; 监控任务」就是 {@code type = RISK_CHECK} 的这一个视图 ——
     * 同一张表两种入口，不另建实体。</p>
     */
    Page<ScheduledTask> findByUserIdAndTaskTypeAndStatusNotOrderByCreatedAtDesc(
            Long userId, ScheduledTaskType taskType, ScheduledTaskStatus status, Pageable pageable);

    /** 取单条时必须同时给用户，避免越权读别人的任务配置。 */
    Optional<ScheduledTask> findByIdAndUserId(Long id, Long userId);

    /** 启动时按库重建调度：只有 ACTIVE 需要被注册。 */
    List<ScheduledTask> findByStatus(ScheduledTaskStatus status);

    /** 创建 / 改名前的重名校验（唯一约束是最终防线，这里是为了给出可读的 400 文案）。 */
    boolean existsByUserIdAndName(Long userId, String name);

    long countByUserIdAndStatusNot(Long userId, ScheduledTaskStatus status);
}
