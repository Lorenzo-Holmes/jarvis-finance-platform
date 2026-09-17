package com.jarvis.research.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条用户自建的定时任务（定义，不是某一次执行）。
 *
 * <p>为什么必须落库而不能靠注解：项目现有 8 处 {@code @Scheduled} 都是编译期固定的，
 * Spring 没有"反注册注解"的 API，做不到"用户创建即生效、暂停即停止"。所以调度器
 * 启动时从本表重建注册，运行时的增删改也以本表为准。</p>
 *
 * <p>{@code nextRunAt} / {@code lastRunAt} / {@code lastRunStatus} / {@code lastError}
 * 是**刻意冗余**的：列表页如果每条任务都去执行历史表捞最近一行，就是典型的 N+1。
 * 这几个字段由内核在每次执行落定后顺带更新，列表页只读本表即可。</p>
 *
 * <p>{@code paramsJson} 只放执行器需要的参数（标的、阈值、周期等），
 * 由服务层用 ObjectMapper 读写；对外始终是结构化 DTO，不让 JSON 字符串穿透到控制器。</p>
 */
@Entity
@Table(name = "scheduled_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 用户可读名称，同一个人下面不允许重名（否则执行历史无法对应）。 */
    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private ScheduledTaskType taskType;

    /** Spring 的 6 段 cron（含秒），不是 Quartz 语法。 */
    @Column(name = "cron_expr", nullable = false, length = 64)
    private String cronExpr;

    /** 本期只允许 Asia/Shanghai，保留该列是为了将来不必改表。 */
    @Column(nullable = false, length = 40)
    private String timezone;

    @Column(name = "params_json", nullable = false, length = 4000)
    private String paramsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScheduledTaskStatus status;

    /** 下次计划触发时刻；ACTIVE 时必填，PAUSED / DELETED 时置空。 */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 16)
    private TaskRunStatus lastRunStatus;

    /** 连续失败次数，供"连续失败达阈值自动暂停"使用；成功一次即清零。 */
    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁：两个标签页同时编辑同一条任务时，后写的那次会拿到异常而不是静默覆盖。 */
    @Version
    @Column(nullable = false)
    private Long version;

    /** 只兜底状态与时间戳，业务字段一律由服务层显式设置。 */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = ScheduledTaskStatus.ACTIVE;
        }
        if (consecutiveFailures == null) {
            consecutiveFailures = 0;
        }
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Shanghai";
        }
        if (paramsJson == null || paramsJson.isBlank()) {
            paramsJson = "{}";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 是否应当被注册进调度器（只有 ACTIVE 需要）。 */
    public boolean isRunnable() {
        return status == ScheduledTaskStatus.ACTIVE;
    }
}
