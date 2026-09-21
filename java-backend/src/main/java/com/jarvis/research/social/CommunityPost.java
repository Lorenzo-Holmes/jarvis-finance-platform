package com.jarvis.research.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "community_post")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CommunityPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;
    @Column(name = "group_id")
    private Long groupId;
    @Column(nullable = false, length = 4000)
    private String content;
    @Column(name = "reference_type", length = 32)
    private String referenceType;
    @Column(name = "reference_id", length = 120)
    private String referenceId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
