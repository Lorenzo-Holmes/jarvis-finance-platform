package com.jarvis.research.ai;

import com.jarvis.research.market.ExtendedMarketDataService;
import com.jarvis.research.market.dto.KlineBarDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ResearchMarketDataGateway} 的实现：适配 {@link ExtendedMarketDataService} 的两个信封。
 *
 * <p>这一层只做两件事——**调对方法**、**把 Map 行规整成 DTO**。
 * 所有"取不到怎么办"的判断都不在这里（那是构建逻辑的事），
 * 这里只保证不把异常扔出去：上游抖一下就返回空，让报告里写清楚缺什么。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketDataGatewayAdapter implements ResearchMarketDataGateway {

    /** 报价与K线都走扩展行情服务：它已经有四个市场统一的降级链与熔断。 */
    private final ExtendedMarketDataService extendedMarketDataService;

    @Override
    public Map<String, Object> quote(String market, String symbol) {
        try {
            return extendedMarketDataService.quote(market, symbol);
        } catch (Exception e) {
            log.warn("研究上下文取报价失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            return null;
        }
    }

    @Override
    public List<KlineBarDTO> dailyBars(String market, String symbol, int limit) {
        try {
            Map<String, Object> envelope =
                    extendedMarketDataService.kline(market, symbol, "1d", limit);
            return toBars(envelope);
        } catch (Exception e) {
            log.warn("研究上下文取日K失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * 信封的 {@code data} 是 {@code List<Map<String,Object>>}，把它转成 DTO。
     *
     * <p>数值一律走宽松解析：行情行里的数字可能是 {@code Double}，
     * 也可能来自落库路径而变成字符串。解析不出来就留 null（那一行仍会被保留，
     * 让 {@link MarketMetrics} 去决定丢不丢），而不是让整段历史消失。</p>
     */
    static List<KlineBarDTO> toBars(Map<String, Object> envelope) {
        if (envelope == null) {
            return List.of();
        }
        Object data = envelope.get("data");
        if (!(data instanceof List<?> rows)) {
            return List.of();
        }
        List<KlineBarDTO> bars = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            bars.add(new KlineBarDTO(
                    text(map.get("date")),
                    number(map.get("open")),
                    number(map.get("close")),
                    number(map.get("high")),
                    number(map.get("low")),
                    number(map.get("volume"))));
        }
        return bars;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}