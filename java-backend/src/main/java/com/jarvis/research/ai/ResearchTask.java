package com.jarvis.research.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次研究任务：请求 + 上下文 + 报告 + 执行元数据。
 *
 * <p>为什么上下文与报告都随任务落库（而不是只存报告）：
 * <ul>
 *   <li>研究结论的可信度取决于当时的输入。"同一份报告为什么得出这个结论"
 *       必须能回看，否则半年后没人能判断是模型变了还是数据变了。</li>
 *   <li>上下文是**确定性**构建的（行情、历史K线、已有分析都由 Java 侧取），
 *       存下来才能区分"数据变了"与"模型变了"。</li>
 * </ul>
 *
 * <p>上下文与报告以 JSON 文本存放，读写由服务层的 ObjectMapper 负责，
 * 对外始终是结构化的 DTO——不让 JSON 字符串穿透到控制器。</p>
 */
@Entity
@Table(name = "research_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 人类可读的标题，列表页用它。 */
    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private ResearchTaskType taskType;

    /** 标的所属市场；纯宏观问题可以为空。 */
    @Column(length = 20)
    private String market;

    /** 标的代码；纯宏观问题可以为空。 */
    @Column(length = 32)
    private String symbol;

    /** 用户提出的研究问题原文。 */
    @Column(nullable = false, length = 2000)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResearchTaskStatus status;

    /** 构建上下文时的输入快照（JSON）。 */
    @Column(name = "context_json", length = 20000)
    private String contextJson;

    /** AI 产出的结构化报告（JSON）。 */
    @Column(name = "report_json", length = 40000)
    private String reportJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 实际使用的模型名，便于对比不同模型的表现。 */
    @Column(length = 64)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    /** 只兜底状态与时间，业务字段一律由服务层显式设置。 */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ResearchTaskStatus.PENDING;
        }
    }

    /** 已经跑完（不论成败）——用于判断能否重跑、能否计费。 */
    public boolean isFinished() {
        return status == ResearchTaskStatus.SUCCEEDED || status == ResearchTaskStatus.FAILED;
    }
}