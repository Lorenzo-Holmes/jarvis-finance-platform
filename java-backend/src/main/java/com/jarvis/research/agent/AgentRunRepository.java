package com.jarvis.research.agent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {

    Optional<AgentRunEntity> findByRunIdAndUserId(String runId, Long userId);

    List<AgentRunEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<AgentRunEntity> findByStatus(String status);
}
