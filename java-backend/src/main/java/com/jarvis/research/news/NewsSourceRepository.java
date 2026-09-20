package com.jarvis.research.news;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NewsSourceRepository extends JpaRepository<NewsSource, Long> {
    List<NewsSource> findAllByOrderByNameAsc();
    List<NewsSource> findAllByEnabledTrueOrderByNameAsc();
    Optional<NewsSource> findBySourceKey(String sourceKey);
    Optional<NewsSource> findByUrl(String url);
}
