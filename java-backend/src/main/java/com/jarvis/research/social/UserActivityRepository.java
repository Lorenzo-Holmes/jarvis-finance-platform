package com.jarvis.research.social;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;

public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {
    Page<UserActivity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    long deleteByUserIdAndReferenceTypeAndReferenceId(Long userId, String referenceType, String referenceId);
    long deleteByReferenceTypeAndReferenceId(String referenceType, String referenceId);
    long deleteByReferenceTypeAndReferenceIdIn(String referenceType, Collection<String> referenceIds);
}
