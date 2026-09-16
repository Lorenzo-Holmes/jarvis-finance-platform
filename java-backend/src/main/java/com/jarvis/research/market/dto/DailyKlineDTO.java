package com.jarvis.research.market.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 日K信封（{@code /api/market/kline?interval=day}，也用于回测与 AI 研究上下文）。
 *
 * <p>为什么不合并成一个 {@code KlineDTO}：日K与分钟K是**两种真实不同的信封**——
 * 日K有 {@code range} 与 {@code as_of}、没有 {@code interval}；分钟K反之。更要紧的是
 * 字段的**存在性语义不同**：窗口为空时 {@code as_of} 仍以 {@code null} 出现（这是既有契约），
 * 而分钟K根本没有这个键。若用一个 DTO 加 {@code @JsonInclude(NON_NULL)} 统一表达，
 * 空窗口的 {@code as_of} 会被静默删掉、凭空改变契约。分成两个 record 才是最忠实的建模。</p>
 *
 * <p>{@code as_of} 的语义是「实际取到的最新一根的日期」，不是请求里传入的截止日：
 * 当窗口有数据时它由数据推导，只有窗口为空时才回落到请求参数。</p>
 */
public record DailyKlineDTO(
        String market,
        KlineRangeDTO range,
        @JsonProperty("as_of") String asOf,
        int count,
        List<KlineBarDTO> data) implements KlineEnvelope {
}