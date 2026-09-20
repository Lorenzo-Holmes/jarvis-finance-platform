package com.jarvis.research.news;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface NewsSubscriptionRepository extends JpaRepository<NewsSubscription, Long> {
    List<NewsSubscription> findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(Long userId);

    @Transactional
    void deleteByUserId(Long userId);
}
