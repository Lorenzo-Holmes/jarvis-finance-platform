package com.jarvis.research.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "community_group_member", uniqueConstraints = @UniqueConstraint(
        name = "uk_community_group_member", columnNames = {"group_id", "user_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CommunityGroupMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "group_id", nullable = false)
    private Long groupId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, length = 16)
    private String role;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
