package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户级功能权限覆盖项。未配置的功能由角色默认策略决定。 */
@Entity
@Table(name = "user_feature_permission",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "feature_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFeaturePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "feature_key", nullable = false, length = 80)
    private String featureKey;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
