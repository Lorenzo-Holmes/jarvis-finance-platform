package com.jarvis.research.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Agent 运行元数据；运行 ID 是对外协议的一部分，所以直接作为主键。 */
@Entity
@Table(name = "agent_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunEntity {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 2000)
    private String question;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "event_count", nullable = false)
    private Integer eventCount;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
