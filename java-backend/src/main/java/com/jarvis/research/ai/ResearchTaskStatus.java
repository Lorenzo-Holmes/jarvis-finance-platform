package com.jarvis.research.ai;

/**
 * 研究任务的状态。
 *
 * <p>刻意是**四态**而不是"进行中/完成"两态：AI 调用是外部依赖，失败必须能和
 * "还没跑"、"正在跑"区分开，否则前端只能显示一个含糊的"没结果"，
 * 而运维也分不清是排队还是失败。</p>
 *
 * <p>存进库的是枚举名（{@code @Enumerated(STRING)}）而不是序号：
 * 以后加状态或调顺序都不会把历史数据读错。</p>
 */
public enum ResearchTaskStatus {

    /** 已创建，尚未开始执行。 */
    PENDING,

    /** 正在构建上下文或调用 AI。 */
    RUNNING,

    /** 报告已生成并存好。 */
    SUCCEEDED,

    /** 执行失败，原因在 errorMessage 里。 */
    FAILED
}