package com.jarvis.research.schedule;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 执行历史仓储。
 *
 * <p>本表只通过 taskId 访问，没有"按 userId 查"的方法 —— 归属校验在
 * {@link ScheduledTaskRepository#findByIdAndUserId} 那一层完成，拿到任务之后
 * 才允许读它的历史。多一条按用户直查的路径就等于多一个绕开校验的入口。</p>
 */
public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRun, Long> {

    /** 某个任务的执行历史，最新在前、分页。 */
    Page<ScheduledTaskRun> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);

    /** 按幂等键取本次执行记录；内核用它判断"这个计划时刻是否已被认领"。 */
    Optional<ScheduledTaskRun> findByIdempotencyKey(String idempotencyKey);

    /**
     * 启动清理用：找出卡在某个状态且开始时间早于阈值的记录。
     *
     * <p>典型场景是进程被强杀，留下 FINISHED_AT 为空的 RUNNING 记录。
     * 不清理它们，界面上会永远显示"运行中"。</p>
     */
    List<ScheduledTaskRun> findByStatusAndStartedAtBefore(TaskRunStatus status, LocalDateTime threshold);

    long countByTaskId(Long taskId);
}
