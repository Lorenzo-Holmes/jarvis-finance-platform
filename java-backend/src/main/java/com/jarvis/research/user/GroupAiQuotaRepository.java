package com.jarvis.research.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GroupAiQuotaRepository extends JpaRepository<GroupAiQuota, Long> {
    Optional<GroupAiQuota> findByGroupId(Long groupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from GroupAiQuota q where q.groupId = :groupId")
    Optional<GroupAiQuota> findByGroupIdForUpdate(@Param("groupId") Long groupId);
}
