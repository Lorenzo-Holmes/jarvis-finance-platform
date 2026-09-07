package com.jarvis.research.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AiQuotaRepository extends JpaRepository<AiQuota, Long> {
    Optional<AiQuota> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from AiQuota q where q.userId = :userId")
    Optional<AiQuota> findByUserIdForUpdate(@Param("userId") Long userId);
}
