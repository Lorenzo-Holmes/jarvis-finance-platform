package com.jarvis.research.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_activity")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "activity_type", nullable = false, length = 32)
    private String activityType;
    @Column(nullable = false, length = 500)
    private String summary;
    @Column(name = "reference_type", length = 32)
    private String referenceType;
    @Column(name = "reference_id", length = 120)
    private String referenceId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
