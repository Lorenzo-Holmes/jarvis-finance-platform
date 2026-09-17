package com.jarvis.research.market.dto;

/**
 * 日K信封里的区间摘要。
 *
 * {@code min} / {@code max} 是**日期字符串**（不是数值），取窗口内最早/最新一根；
 * 窗口为空时两者为 null——这一点是既有对外契约，由 {@code MarketApiContractTest} 钉住。
 */
public record KlineRangeDTO(String min, String max, int count) {
}