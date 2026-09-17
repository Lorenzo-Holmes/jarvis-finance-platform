package com.jarvis.research.ai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 研究任务仓储。
 *
 * <p>所有查询都**带 userId**：研究记录是私人内容，一条"按 id 取任务"的便捷方法
 * 迟早会被用在控制器里，而那样就能读到别人的报告。所以这里不提供
 * {@code findById} 之外的无主查询，只给 {@link #findByIdAndUserId}——
 * 越权在这层就做不到，而不是靠每个调用点记得校验。</p>
 */
public interface ResearchTaskRepository extends JpaRepository<ResearchTask, Long> {

    /** 历史研究记录：最新在前，分页。 */
    Page<ResearchTask> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 取单条时必须同时给用户，避免越权读别人的报告。 */
    Optional<ResearchTask> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, ResearchTaskStatus status);
}