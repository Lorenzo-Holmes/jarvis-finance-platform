package com.jarvis.research.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentEventRepository extends JpaRepository<AgentEventEntity, Long> {

    List<AgentEventEntity> findByRunIdOrderBySequenceAsc(String runId);
}
