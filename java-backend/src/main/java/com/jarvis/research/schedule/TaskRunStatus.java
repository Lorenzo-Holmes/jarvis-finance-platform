package com.jarvis.research.schedule;

/**
 * 单次执行的结果。
 *
 * <p>{@link #SKIPPED} 是本设计里最容易被误读的一态，值得单列说明：它不是失败，
 * 而是**幂等机制的正常输出**。到点触发时先按幂等键插一行 RUNNING，插不进去说明
 * 这个计划时刻已经被处理过（调度重建、时钟回拨、或用户连点"立即执行"），
 * 于是记一行 SKIPPED 就返回。把它和 FAILED 混在一起会让"失败率"这张图失去意义。</p>
 */
public enum TaskRunStatus {

    /** 已认领幂等键，正在执行；finishedAt 为空。 */
    RUNNING,

    /** 执行器正常返回。 */
    SUCCESS,

    /** 执行器抛异常，原因写在 errorType / errorMessage。 */
    FAILED,

    /** 幂等键冲突：这次计划时刻已被处理过，未真正执行。 */
    SKIPPED,

    /**
     * 超过单次执行上限，或进程被强杀后由启动清理改判。
     *
     * <p>FINISHED_AT 为空的 RUNNING 记录是"崩溃遗留"，启动时必须扫出来改判成本状态，
     * 否则它会永远显示"运行中"。</p>
     */
    TIMEOUT
}
