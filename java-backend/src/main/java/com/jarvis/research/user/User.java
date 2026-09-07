package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    /** BCrypt 加密后的密码 */
    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 60)
    private String displayName;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** 账户角色：USER / ADMIN。权限判断必须在后端完成。 */
    @Builder.Default
    @Column(nullable = false, length = 24)
    private String role = "USER";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
