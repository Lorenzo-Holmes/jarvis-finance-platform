package com.jarvis.research.schedule;

/**
 * 定时任务的执行器（SPI）。
 *
 * <p>每种 {@link ScheduledTaskType} 由唯一一个实现认领。内核不关心任务具体做什么 ——
 * 它只负责「到点、防重、派发、留痕」这四件事，业务逻辑全在执行器里。</p>
 *
 * <p>为什么要有这层 SPI 而不是把逻辑写进内核：任务类型是**会继续增加的**
 * （PRD 里已经列了资讯汇总 / 行情检查 / 风险扫描 / 策略回测 / 报告生成），
 * 而"新增一种任务"不应该需要改动调度代码 —— 加一个实现类、注册进 Spring 即可。</p>
 *
 * <p>⚠️ 实现约定：</p>
 * <ul>
 *   <li>执行器<strong>由内核决定何时被调用</strong>，自己不要起线程、不要 sleep 等下一次；</li>
 *   <li>失败请<strong>抛异常</strong>：内核会捕获并记 {@link TaskRunStatus#FAILED} + 异常类型。
 *       返回一个"内容表示失败"的结果对象会被当成成功 —— 这是刻意的，
 *       结果内容的解释权在执行器，而"出没出错"由异常表达；</li>
 *   <li>执行器<strong>不写 run 记录</strong>：状态由内核统一维护，避免两处状态各说各话。</li>
 * </ul>
 */
public interface ScheduledTaskExecutor {

    /** 本执行器认领的任务类型。 */
    ScheduledTaskType type();

    /**
     * 执行一次。
     *
     * @param task 任务定义，{@code paramsJson} 由执行器自行解析
     * @return 结果摘要与产物引用
     */
    TaskExecutionResult execute(ScheduledTask task);
}
