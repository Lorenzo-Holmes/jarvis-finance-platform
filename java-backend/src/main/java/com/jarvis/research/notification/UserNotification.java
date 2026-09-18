package com.jarvis.research.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条站内通知。
 *
 * <p>定位是「提醒」，不是「第二份执行历史」：{@code title} 一句话、{@code body} 是摘要，
 * 完整结果仍在 {@code scheduled_task_run} 里 —— 本表只存**引用**
 * （{@code linkKind} + {@code linkRef}）。同一份结果两处存储必然两处不一致。</p>
 *
 * <p><strong>合并语义</strong>（{@link #repeatCount} / {@link #lastSeenAt}）：
 * 同一个任务反复失败时，10 分钟窗口内不再新写一行，而是累加 {@code repeatCount}
 * 并把 {@code lastSeenAt} 推到当前、{@code readAt} 清空（它又发生了，理应重新变未读）。
 * {@code createdAt} 保留**首次**发生时间 —— 这样用户既能看到"最早什么时候坏的"，
 * 也能从 {@code repeatCount} 看出"还在持续坏"，而列表不会被同一故障刷屏。</p>
 *
 * <p>⚠️ {@code dedupKey} <strong>没有唯一约束</strong>，这是刻意的：去重语义是"窗口内合并"，
 * 窗口之外应当允许再写一条（"上周坏过一次、这周又坏了"是两件事）。
 * 加唯一约束会把它变成"永远只留一条"，反而抹掉信息。迁移脚本里有同样的注释。</p>
 */
@Entity
@Table(name = "user_notification")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收件人。越权一律在仓储层就取不到（查询一律带 userId）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationLevel level;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 1000)
    private String body;

    /** 引用类型（如 {@code SCHEDULED_TASK} / {@code SCHEDULED_TASK_RUN}），前端据此决定点进去看什么。 */
    @Column(name = "link_kind", length = 24)
    private String linkKind;

    @Column(name = "link_ref")
    private Long linkRef;

    /** 去重键：{@code <userId>:<type>:<refId>}。窗口内查找用，**非唯一**（见类注释）。 */
    @Column(name = "dedup_key", nullable = false, length = 128)
    private String dedupKey;

    /** 合并次数；首次写入为 1。 */
    @Column(name = "repeat_count", nullable = false)
    private Integer repeatCount;

    /** 已读时间；NULL 表示未读。 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** **首次**发生时间（合并时刻意不改它）。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** **最近一次**发生时间，列表按它倒序。 */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = createdAt;
        }
        if (repeatCount == null) {
            repeatCount = 1;
        }
    }

    public boolean isUnread() {
        return readAt == null;
    }
}
