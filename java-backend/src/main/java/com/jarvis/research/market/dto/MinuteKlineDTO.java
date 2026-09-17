package com.jarvis.research.market.dto;

import java.util.List;

/**
 * 分钟K信封（{@code /api/market/kline?interval=1|5|15|30|60}，以及积存金分钟K）。
 *
 * <p>与 {@link DailyKlineDTO} 是两种不同形状：这里用 {@code interval}（如 {@code "5m"}）
 * 代替 {@code range}/{@code as_of}——分钟窗口的「区间」由 interval 与根数即可确定，
 * 不需要再给日期上下界。</p>
 *
 * <p>分钟K由实时价格快照按分钟桶聚合而来，因此每根的 {@code volume} 恒为 0.0
 * （快照不含成交量），这不是缺数据而是该口径的固有含义。</p>
 */
public record MinuteKlineDTO(
        String market,
        String interval,
        int count,
        List<KlineBarDTO> data) implements KlineEnvelope {
}