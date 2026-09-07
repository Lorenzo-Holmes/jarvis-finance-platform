package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** 用户 AI 配额：每日请求次数与自然月 Token 用量。 */
@Entity
@Table(name = "ai_quota", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Builder.Default
    @Column(name = "daily_request_limit", nullable = false)
    private int dailyRequestLimit = 100;

    @Builder.Default
    @Column(name = "daily_request_used", nullable = false)
    private int dailyRequestUsed = 0;

    /** 0 表示不启用 Token 限额。 */
    @Builder.Default
    @Column(name = "monthly_token_limit", nullable = false)
    private long monthlyTokenLimit = 0L;

    @Builder.Default
    @Column(name = "monthly_token_used", nullable = false)
    private long monthlyTokenUsed = 0L;

    @Column(name = "reset_date", nullable = false)
    private LocalDate resetDate;

    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDate today = LocalDate.now();
        if (resetDate == null) resetDate = today;
        if (periodMonth == null) periodMonth = YearMonth.from(today).toString();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }
}
