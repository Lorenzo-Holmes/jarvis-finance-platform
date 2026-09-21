package com.jarvis.research.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    List<AuditEvent> findByUserIdAndActionInOrderByCreatedAtDesc(
            Long userId, Collection<String> actions, Pageable pageable);
}
