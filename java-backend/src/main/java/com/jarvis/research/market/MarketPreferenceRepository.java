package com.jarvis.research.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketPreferenceRepository extends JpaRepository<MarketPreference, Long> {
    Optional<MarketPreference> findByUserId(Long userId);
}
