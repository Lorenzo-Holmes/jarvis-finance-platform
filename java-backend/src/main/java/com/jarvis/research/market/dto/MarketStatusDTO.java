package com.jarvis.research.market.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 交易时段状态（A股 / 美股 / 加密货币）。
 *
 * 替代原先的 {@code Map<String, Object>}：字段名、类型与必需的键都在编译期确定，
 * 不再依赖「记得往 map 里 put 哪几个键」。
 *
 * JSON 键名与迁移前**逐字一致**（{@code is_open} / {@code checked_at} 用
 * {@link JsonProperty} 映射），由 {@code MarketApiContractTest} 在 JSON 层面钉住，
 * 因此这次替换对前端完全透明。
 *
 * @param market     标准化后的市场标识
 * @param isOpen     是否处于可交易时段
 * @param status     {@code open} / {@code closed}
 * @param label      面向用户的中文状态文案
 * @param timezone   估算所用时区
 * @param checkedAt  估算时刻
 * @param disclaimer 口径说明（未接入交易所节假日历）
 */
public record MarketStatusDTO(
        String market,
        @JsonProperty("is_open") boolean isOpen,
        String status,
        String label,
        String timezone,
        @JsonProperty("checked_at") String checkedAt,
        String disclaimer) {

    /** 与迁移前一致的兜底文案，避免调用方各自硬编码。 */
    public static final String DEFAULT_CLOSED_LABEL = "非交易时段";
}