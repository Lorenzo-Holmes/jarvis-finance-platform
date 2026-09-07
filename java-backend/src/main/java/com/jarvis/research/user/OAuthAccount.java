package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 第三方登录绑定；只保存 provider 用户标识，不保存 OAuth access token。 */
@Entity
@Table(name = "oauth_account", uniqueConstraints = {
        @UniqueConstraint(name = "uk_oauth_provider_subject", columnNames = {"provider", "provider_user_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 128)
    private String providerUserId;

    @Column(name = "provider_login", length = 120)
    private String providerLogin;

    @Column(length = 120)
    private String email;

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
