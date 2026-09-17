package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.market.MarketDataService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 行情扫描执行器：按配置的标的取最新报价，挑出波动超过阈值的那些。
 *
 * <p>刻意<strong>只读内存里已有的行情缓存</strong>（{@code getLatestPrices()}），
 * 不自己去拉上游。原因：秒级采集已经由 {@code MarketDataService} 的常驻任务在做，
 * 执行器再拉一遍等于重复访问上游、还可能触发对方的限流。
 * 这里要的是"按用户设定的节奏检查一次现有数据"，不是"再采集一次"。</p>
 *
 * <p>参数（{@code params_json}）：</p>
 * <pre>
 * {
 *   "markets": ["gold_etf", "london_gold"],   // 可选，缺省扫全部
 *   "changeThresholdPct": 1.5                  // 可选，超过则视为值得关注
 * }
 * </pre>
 */
@Slf4j
@Component
public class MarketScanExecutor implements ScheduledTaskExecutor {

    private static final List<String> DEFAULT_MARKETS = List.of("gold_etf", "london_gold");

    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;

    public MarketScanExecutor(MarketDataService marketDataService, ObjectMapper objectMapper) {
        this.marketDataService = marketDataService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScheduledTaskType type() {
        return ScheduledTaskType.MARKET_SCAN;
    }

    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        MarketScanParams params = parseParams(task.getParamsJson());
        List<String> markets = (params.getMarkets() == null || params.getMarkets().isEmpty())
                ? DEFAULT_MARKETS
                : params.getMarkets();

        Map<String, Object> prices = marketDataService.getLatestPrices();

        List<String> observed = new ArrayList<>();
        List<Map<String, Object>> alerts = new ArrayList<>();

        for (String market : markets) {
            Map<String, Object> quote = asMap(prices.get(market));
            if (quote == null) {
                continue;
            }
            Double price = asDouble(quote.get("price"));
            Double changePct = asDouble(quote.get("change_pct"));
            boolean stale = Boolean.TRUE.equals(quote.get("stale"));

            observed.add(String.format(Locale.ROOT, "%s %s%s",
                    market,
                    price == null ? "—" : String.format(Locale.ROOT, "%.4f", price),
                    changePct == null ? "" : String.format(Locale.ROOT, " (%+.4f%%)", changePct))
                    + (stale ? "[陈旧]" : ""));

            Double threshold = params.getChangeThresholdPct();
            if (threshold != null && changePct != null && Math.abs(changePct) >= threshold) {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("market", market);
                alert.put("price", price);
                alert.put("changePct", changePct);
                alert.put("stale", stale);
                alerts.add(alert);
            }
        }

        if (observed.isEmpty()) {
            // "拿不到行情"不算执行失败（失败用异常表达），但必须在摘要里说清楚，
            // 否则用户在历史里只看到一句无信息量的"完成"。
            return TaskExecutionResult.of("行情扫描完成，但当前没有任何可用报价（缓存为空或标的未采集）");
        }

        String summary = "行情扫描完成：" + String.join("｜", observed);
        if (!alerts.isEmpty()) {
            Double threshold = params.getChangeThresholdPct();
            summary = String.format(Locale.ROOT, "行情扫描发现 %d 个标的波动超过阈值 %.4f%%：%s",
                    alerts.size(), threshold, String.join("｜", observed));
        }

        return alerts.isEmpty()
                ? TaskExecutionResult.of(summary)
                : TaskExecutionResult.withArtifacts(summary, writeJson(alerts));
    }

    private MarketScanParams parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new MarketScanParams();
        }
        try {
            MarketScanParams parsed = objectMapper.readValue(paramsJson, MarketScanParams.class);
            return parsed == null ? new MarketScanParams() : parsed;
        } catch (Exception invalid) {
            // 参数脏不该让任务直接失败成 FAILED —— 用默认值跑完并留下告警更有用。
            log.warn("行情扫描参数解析失败，改用默认值。params={}", paramsJson);
            return new MarketScanParams();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 任务参数。字段全部可空，缺省时用默认行为。 */
    @Data
    public static class MarketScanParams {
        private List<String> markets;
        private Double changeThresholdPct;
    }
}
