package com.jarvis.research.market;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 扩展市场行情的持久化缓存。payload 由 Java 统一序列化，行情源故障时只读此缓存并明确标记 stale。
 */
@Entity
@Table(name = "market_data_cache", uniqueConstraints = {
        @UniqueConstraint(name = "uk_market_data_cache_key", columnNames = "cache_key")
}, indexes = {
        @Index(name = "idx_market_data_cache_lookup", columnList = "market,symbol,kind,bar_interval")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDataCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cache_key", nullable = false, length = 300, unique = true)
    private String cacheKey;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 32)
    private String market;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(name = "bar_interval", length = 8)
    private String interval;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(length = 80)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
