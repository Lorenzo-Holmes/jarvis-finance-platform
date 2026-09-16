package com.jarvis.research.market.dto;

/**
 * 单根K线。
 *
 * 日K与分钟K共用同一形状，字段名与迁移前**逐字一致**：
 * {@code date} / {@code open} / {@code close} / {@code high} / {@code low} / {@code volume}。
 *
 * {@code date} 的格式随来源而异：日K是 {@code yyyy-MM-dd}，分钟K是聚合桶键
 * {@code yyyy-MM-dd HH:mm}。这里不强行统一——格式差异属于各接口既有的对外契约。
 *
 * 价格用包装类型 {@code Double} 而非基本类型：日K来自数据库实体，理论上可能为空，
 * 用 null 表达「该行缺这个字段」比用 0 诚实。
 */
public record KlineBarDTO(
        String date,
        Double open,
        Double close,
        Double high,
        Double low,
        Double volume) {
}