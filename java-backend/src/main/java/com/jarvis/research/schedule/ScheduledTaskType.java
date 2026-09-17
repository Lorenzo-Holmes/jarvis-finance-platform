package com.jarvis.research.schedule;

import java.util.Locale;

/**
 * 定时任务的类型：决定由哪个执行器（SPI）认领这次触发。
 *
 * <p>用枚举而不是自由字符串：类型决定的是代码路径，写错的类型应当在创建任务时就 400，
 * 而不是等调度到点、没有任何执行器认领时才在历史里留下一行莫名失败。</p>
 *
 * <p>与 {@code ResearchTaskType} 的区别：那是"用户手动发起一次研究请求"的类型，
 * 这是"按周期自动重复"的任务类型。两者中文都容易被叫成"任务"，代码里必须区分开。</p>
 */
public enum ScheduledTaskType {

    /** 行情扫描：按标的取最新报价，命中阈值条件时产出结果。 */
    MARKET_SCAN,

    /** 风险检测：复用模拟盘风控与风险预警链路。 */
    RISK_CHECK,

    /** 策略回测：复用既有回测服务，属分钟级耗时任务。 */
    BACKTEST,

    /** 每日资讯日报：产物来自 RSS 侧，执行器由信息中心模块提供。 */
    DAILY_DIGEST;

    /**
     * 宽松解析：容忍大小写与首尾空白，认不出来返回 null。
     *
     * <p>返回 null 而不是抛异常，是为了让控制器能给出统一的 400 文案，
     * 而不是把 {@code IllegalArgumentException} 泄成 500。</p>
     */
    public static ScheduledTaskType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ScheduledTaskType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
