package com.jarvis.research.news;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 持久化的 RSS 资讯源；抓取由 Python 执行，配置和权限由 Java 管理。 */
@Entity
@Table(name = "news_source", uniqueConstraints = {
        @UniqueConstraint(columnNames = "source_key"),
        @UniqueConstraint(columnNames = "url")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_key", nullable = false, unique = true, length = 80)
    private String sourceKey;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 500)
    private String url;

    @Builder.Default
    @Column(nullable = false, length = 40)
    private String category = "general";

    @Builder.Default
    @Column(nullable = false)
    private Integer credibility = 50;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

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
