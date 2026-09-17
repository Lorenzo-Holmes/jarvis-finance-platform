package com.jarvis.research.ai;

import com.jarvis.research.market.dto.KlineBarDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究上下文的构建器：把一条任务变成 AI 能用的"事实快照"。
 *
 * <p>设计上只有一条硬要求：**绝不因为取不到数据而抛异常**。
 * 休市、上游抖动、标的刚上市都只该在 {@link ResearchContext#warnings()} 里留下一句说明，
 * 报告照常生成并如实交代"这部分数据没取到"。让一次行情抖动把整个研究任务变成 FAILED，
 * 是把数据层的问题错误地升级成了业务层的问题。</p>
 *
 * <p>指标全部来自 {@link MarketMetrics}（纯函数），构建器自己不做算术。</p>
 */
@Component
@RequiredArgsConstructor
public class ResearchContextBuilder {

    private final ResearchMarketDataGateway gateway;

    /** 指标键名。集中在这里，避免服务层、提示词与前端各写一套字符串。 */
    public static final String LATEST_CLOSE = "latest_close";
    public static final String CHANGE_PCT = "change_pct";
    public static final String PERIOD_CHANGE_PCT = "period_change_pct";
    public static final String PERIOD_HIGH = "period_high";
    public static final String PERIOD_LOW = "period_low";
    public static final String AVERAGE_VOLUME = "average_volume";
    public static final String MA5 = "ma5";
    public static final String MA10 = "ma10";
    public static final String MA20 = "ma20";
    public static final String VOLATILITY = "annualized_volatility";
    public static final String MAX_DRAWDOWN_PCT = "max_drawdown_pct";

    public ResearchContext build(ResearchTask task) {
        LocalDateTime asOf = LocalDateTime.now();
        String market = task.getMarket();
        String symbol = task.getSymbol();

        // 宏观问题没有标的：这不是"取数失败"，而是本来就不需要行情。
        // 用一句明确的话说清楚，免得报告里出现听起来像故障的"数据缺失"。
        if (symbol == null || symbol.isBlank() || market == null || market.isBlank()) {
            return new ResearchContext(market, symbol, null, asOf, 0, null, null,
                    null, Map.of(), List.of("该任务没有指定标的，未取行情数据"));
        }

        List<String> warnings = new ArrayList<>();

        Map<String, Object> quote = gateway.quote(market, symbol);
        if (quote == null || quote.isEmpty() || quote.containsKey("error")) {
            warnings.add("未取到最新报价");
            quote = null;
        }

        List<KlineBarDTO> bars = gateway.dailyBars(market, symbol, ResearchContext.DAILY_BAR_LIMIT);
        if (bars.isEmpty()) {
            warnings.add("未取到日K数据，无法计算技术指标");
        } else if (bars.size() < 20) {
            warnings.add("日K仅 " + bars.size() + " 根，部分指标（如20日均线）无法计算");
        }

        Map<String, Object> metrics = metrics(bars);
        if (!bars.isEmpty() && !metrics.containsKey(VOLATILITY)) {
            // 说清楚"算不出来"而不是留空让人以为波动率是 0
            warnings.add("样本不足，未能计算年化波动率");
        }

        return new ResearchContext(market, symbol, "1d", asOf, bars.size(),
                bars.isEmpty() ? null : bars.get(0).date(),
                bars.isEmpty() ? null : bars.get(bars.size() - 1).date(),
                quote, metrics, warnings);
    }

    /**
     * 把指标装成 map，**算不出来的键直接不出现**。
     *
     * <p>刻意不放 {@code null} 值：{@code {"volatility": null}} 在提示词里容易被读成
     * "波动率为零"或"波动率未知"两种意思，而键不出现只可能是"没这个信息"。
     * 语义上的含糊在交给模型的数据里代价很高。</p>
     */
    private Map<String, Object> metrics(List<KlineBarDTO> bars) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        put(metrics, LATEST_CLOSE, MarketMetrics.latestClose(bars));
        put(metrics, CHANGE_PCT, MarketMetrics.changePct(bars));
        put(metrics, PERIOD_CHANGE_PCT, MarketMetrics.periodChangePct(bars));
        put(metrics, PERIOD_HIGH, MarketMetrics.periodHigh(bars));
        put(metrics, PERIOD_LOW, MarketMetrics.periodLow(bars));
        put(metrics, AVERAGE_VOLUME, MarketMetrics.averageVolume(bars));
        put(metrics, MA5, MarketMetrics.movingAverage(bars, 5));
        put(metrics, MA10, MarketMetrics.movingAverage(bars, 10));
        put(metrics, MA20, MarketMetrics.movingAverage(bars, 20));
        put(metrics, VOLATILITY, MarketMetrics.volatility(bars));
        put(metrics, MAX_DRAWDOWN_PCT, MarketMetrics.maxDrawdownPct(bars));
        return metrics;
    }

    private static void put(Map<String, Object> metrics, String key, Double value) {
        if (value != null) {
            metrics.put(key, value);
        }
    }
}