package com.jarvis.research.schedule;

/**
 * 一次执行的触发来源。
 *
 * <p>为什么要记下来：同一条执行历史，用户看到"我没让它跑啊"和执行器看到"确实跑过"
 * 之间的分歧，只能靠这一列解释。它也参与幂等键的构造 —— 手动执行带独立后缀，
 * 不会被同一时刻的计划执行挤掉。</p>
 */
public enum TaskTriggerType {

    /** 由调度器按 cron 到点触发。 */
    SCHEDULED,

    /** 用户在界面上点了"立即执行"。 */
    MANUAL,

    /** 上一次失败后的补偿重试。 */
    RETRY
}
