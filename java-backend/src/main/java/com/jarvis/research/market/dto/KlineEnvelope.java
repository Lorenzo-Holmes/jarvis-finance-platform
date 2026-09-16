package com.jarvis.research.market.dto;

/**
 * K线信封的联合类型。
 *
 * {@code /api/market/kline} 按 {@code interval} 参数返回两种不同形状之一：
 * {@code day} 走 {@link DailyKlineDTO}，{@code 1/5/15/30/60} 走 {@link MinuteKlineDTO}。
 * 用密封接口表达这个联合，比退回 {@code ApiResponse<Object>} 更诚实：
 * 调用方能看出「只可能是这两种之一」，而序列化结果与迁移前完全一致。
 */
public sealed interface KlineEnvelope permits DailyKlineDTO, MinuteKlineDTO {

    /** 信封对应的市场，两种形状都有。 */
    String market();

    /** 返回的K线根数，两种形状都有。 */
    int count();
}