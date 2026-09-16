package com.jarvis.research.ai;

import java.util.Locale;

/**
 * 研究任务的类型：决定要构建什么上下文、以及要求 AI 产出哪种报告。
 *
 * <p>取值对应既有的分析能力（{@code /api/ai/analyze/*} 那批单次接口），
 * 但在这里它们是**工作流的类型**：每种类型有自己的上下文片段组合与报告结构。
 * 单次接口是"问一句答一句"，任务类型是"按套路做完一件事并留档"。</p>
 *
 * <p>用枚举而不是自由字符串：类型决定的是代码路径，写错的类型应当在入口就 400，
 * 而不是等 AI 拿到一个没人认识的名字后编出一份不像话的报告。</p>
 */
public enum ResearchTaskType {

    /** 综合研究报告：行情+历史+已有分析，给一份完整判断。 */
    REPORT,

    /** 市场情绪分析。 */
    SENTIMENT,

    /** 产业链/关联标的分析。 */
    CHAIN,

    /** 风险分析。 */
    RISK,

    /** 趋势研判。 */
    TREND,

    /** 策略讨论。 */
    STRATEGY;

    /**
     * 宽松解析：大小写与首尾空白都容忍，认不出来返回 null。
     *
     * <p>返回 null 而不是抛异常，是为了让调用方（控制器）能给出统一的 400 文案，
     * 而不是把 {@code IllegalArgumentException} 泄成 500。</p>
     */
    public static ResearchTaskType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ResearchTaskType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}