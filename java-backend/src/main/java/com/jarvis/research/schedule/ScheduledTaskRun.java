package com.jarvis.research.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次执行的留痕。
 *
 * <p>{@code idempotencyKey} 上的唯一约束是整套防重复执行机制的支点：到点触发时
 * <strong>先插这一行再执行</strong>，插得进去才算抢到；冲突就说明这个计划时刻已被处理过。
 * 所以本表既是审计记录，也是并发控制的手段 —— 这是刻意让"历史"和"锁"落在同一张表上，
 * 免得两处状态各说各话。</p>
 *
 * <p>⚠️ <strong>该唯一约束必须在实体上声明</strong>，不能只写在 Flyway 脚本里：
 * 本地开发（{@code ddl-auto=update}）与 {@code @DataJpaTest} 都是按实体建表的，
 * 只在 SQL 里写会让<strong>本地的约束凭空消失</strong> —— 于是防重在本机静默失效，
 * 而生产却有约束，两边行为不一致却看不出来。（2026-09-17 实测踩到。）</p>
 *
 * <p>本实体<strong>没有 {@code @Version}</strong>：run 记录一旦落定就不再被并发编辑，
 * 乐观锁在这里没有意义，加上只会多一次无谓的版本号自增。</p>
 */
@Entity
@Table(name = "scheduled_task_run", uniqueConstraints = @UniqueConstraint(
        name = "uk_task_run_idempotency", columnNames = "idempotency_key"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledTaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private TaskTriggerType triggerType;

    /** 计划触发时刻（不是实际开始时刻），幂等键的组成部分。 */
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** RUNNING 时为空。 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskRunStatus status;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** 人类可读的结果摘要，列表页直接展示。 */
    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    /** 产物**引用**（站内链接 / 记录 ID）的 JSON，产物本体另存。 */
    @Column(name = "artifacts_json", length = 4000)
    private String artifactsJson;

    /** 异常类名，便于按类型归类统计。 */
    @Column(name = "error_type", length = 120)
    private String errorType;

    /** 截断后的异常信息，完整堆栈在应用日志里。 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = TaskRunStatus.RUNNING;
        }
    }

    /** 已落定（不论成败）——用于判断是否会再被更新。 */
    public boolean isFinished() {
        return status != TaskRunStatus.RUNNING;
    }
}
