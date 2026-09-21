package com.jarvis.research.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "community_group")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CommunityGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(length = 500)
    private String description;
    @Column(nullable = false, length = 16)
    private String visibility;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @PrePersist void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (visibility == null || visibility.isBlank()) visibility = "OPEN";
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
