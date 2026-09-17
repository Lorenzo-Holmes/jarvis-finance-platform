package com.jarvis.research.schedule;

/**
 * 一次执行的结果。
 *
 * <p>{@code summary} 是给人看的一句话（列表页直接展示），
 * {@code artifactsJson} 是给程序看的产物**引用**（不是产物本体，避免 run 表膨胀）。</p>
 *
 * <p>刻意不含"成功与否"字段：那由异常表达 —— 执行器正常返回就是成功。
 * 如果这里放一个 {@code success} 标志，就会出现"返回 success=true 但 summary 写着失败"
 * 这类自相矛盾的数据，而内核无法判断该信哪个。</p>
 *
 * @param summary       人类可读的结果摘要，一句话
 * @param artifactsJson 产物引用的 JSON（可为 null）
 */
public record TaskExecutionResult(String summary, String artifactsJson) {

    /** 只有摘要、没有产物的常见情况。 */
    public static TaskExecutionResult of(String summary) {
        return new TaskExecutionResult(summary, null);
    }

    public static TaskExecutionResult withArtifacts(String summary, String artifactsJson) {
        return new TaskExecutionResult(summary, artifactsJson);
    }
}
