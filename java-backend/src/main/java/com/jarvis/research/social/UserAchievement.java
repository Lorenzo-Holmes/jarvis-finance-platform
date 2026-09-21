package com.jarvis.research.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_achievement", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_achievement", columnNames = {"user_id", "achievement_key"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserAchievement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "achievement_key", nullable = false, length = 48)
    private String achievementKey;
    @Column(name = "unlocked_at", nullable = false)
    private LocalDateTime unlockedAt;
    @PrePersist void onCreate() { if (unlockedAt == null) unlockedAt = LocalDateTime.now(); }
}
