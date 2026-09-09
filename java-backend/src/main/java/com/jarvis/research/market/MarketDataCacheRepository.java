package com.jarvis.research.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketDataCacheRepository extends JpaRepository<MarketDataCache, Long> {
    Optional<MarketDataCache> findByCacheKey(String cacheKey);
}
