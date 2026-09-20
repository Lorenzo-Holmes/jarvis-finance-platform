package com.jarvis.research.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Agent 事件持久化模型；展示协议 AgentEvent 与数据库模型分离。 */
@Entity
@Table(name = "agent_event", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_event_sequence", columnNames = {"run_id", "sequence"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "step_id", nullable = false, length = 64)
    private String stepId;

    @Column(nullable = false)
    private Long sequence;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 200)
    private String title;

    @Column(length = 100)
    private String tool;

    @Column(name = "input_summary", length = 2000)
    private String inputSummary;

    @Column(name = "output_summary", length = 2000)
    private String outputSummary;

    @Column(name = "payload_json", length = 40000)
    private String payloadJson;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 64)
    private String errorCode;
}
