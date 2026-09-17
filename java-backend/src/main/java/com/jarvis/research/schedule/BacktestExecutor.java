package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.service.BacktestService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 策略回测执行器：按周期跑一次双均线回测，把绩效指标记进执行历史。
 *
 * <p>刻意<strong>不自己实现回测</strong>，而是直接调 {@link BacktestService}：
 * 同一套策略在同一时间窗口跑出两个数字（页面上一个、定时任务里一个）是最糟的结果，
 * 用户没法判断该信哪个。回测的口径（手续费率 {@code 0.001}、双均线版本号、
 * 数据指纹算法）全部留在那一个类里。</p>
 *
 * <p>参数（{@code params_json}）：</p>
 * <pre>
 * {
 *   "market": "gold_etf",     // 可选，默认 gold_etf
 *   "shortMa": 5,             // 可选，默认 5
 *   "longMa": 20,             // 可选，默认 20
 *   "initialCash": 100000,    // 可选，默认 100000
 *   "limit": 120,             // 可选，默认 120；必须 >= longMa 且 <= 5000
 *   "asOf": "2026-09-16"      // 可选，回测截止日，用于可复现的定时回测
 * }
 * </pre>
 *
 * <p>⚠️ 日 K 只有 {@code MarketDataService} 一处落库，覆盖 {@code gold_etf} 与
 * {@code london_gold}；其他市场（A股/美股/加密）的 K 线是按需实时抓的、不落库，
 * 因此这里不额外维护一份"可回测市场白名单"—— 白名单会随行情层改动而失效，
 * 数据不足时 {@link BacktestService} 会抛出可读的异常，比一份会腐化的名单可靠。</p>
 */
@Slf4j
@Component
public class BacktestExecutor implements ScheduledTaskExecutor {

    private static final String DEFAULT_MARKET = "gold_etf";
    private static final int DEFAULT_SHORT_MA = 5;
    private static final int DEFAULT_LONG_MA = 20;
    private static final double DEFAULT_INITIAL_CASH = 100_000.0;
    private static final int DEFAULT_LIMIT = 120;

    private final BacktestService backtestService;
    private final ObjectMapper objectMapper;

    public BacktestExecutor(BacktestService backtestService, ObjectMapper objectMapper) {
        this.backtestService = backtestService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScheduledTaskType type() {
        return ScheduledTaskType.BACKTEST;
    }

    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        BacktestParams params = parseParams(task.getParamsJson());

        String market = params.getMarket() == null || params.getMarket().isBlank()
                ? DEFAULT_MARKET
                : params.getMarket().trim();
        int shortMa = params.getShortMa() == null ? DEFAULT_SHORT_MA : params.getShortMa();
        int longMa = params.getLongMa() == null ? DEFAULT_LONG_MA : params.getLongMa();
        double initialCash = params.getInitialCash() == null ? DEFAULT_INITIAL_CASH : params.getInitialCash();
        int limit = params.getLimit() == null ? DEFAULT_LIMIT : params.getLimit();
        String asOf = params.getAsOf() == null || params.getAsOf().isBlank() ? null : params.getAsOf().trim();

        if (limit < longMa) {
            // 交给 BacktestService 会报一句"limit 必须在 long_ma ~ 5000 之间"，
            // 但用户看不出是"任务配置里 limit 太小"还是"系统数据不够"，所以这里先自己判。
            throw new IllegalArgumentException(
                    "回测参数不合法：limit(" + limit + ") 不能小于 longMa(" + longMa + ")");
        }

        Map<String, Object> report = backtestService.run(market, shortMa, longMa, initialCash, limit, asOf);
        if (report == null) {
            throw new IllegalStateException("回测服务返回空结果");
        }

        String summary = describe(market, shortMa, longMa, report);
        String artifacts = writeJson(artifacts(report, market, shortMa, longMa));

        log.info("定时回测完成。taskId={} market={} {}/{} 总收益={}%",
                task.getId(), market, shortMa, longMa, report.get("total_return_pct"));
        return new TaskExecutionResult(summary, artifacts);
    }

    /** 一句话摘要：定时任务的价值就是"不用点开也能看出这次跑出什么"。 */
    private static String describe(String market, int shortMa, int longMa, Map<String, Object> report) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "双均线回测 %s %d/%d：", market, shortMa, longMa));
        text.append("总收益 ").append(pct(report.get("total_return_pct")));
        text.append("｜年化 ").append(pct(report.get("annual_return_pct")));
        text.append("｜最大回撤 ").append(pct(report.get("max_drawdown_pct")));

        Double strategy = asDouble(report.get("total_return_pct"));
        Double buyHold = asDouble(report.get("buy_hold_return_pct"));
        if (strategy != null && buyHold != null) {
            double diff = strategy - buyHold;
            text.append(String.format(Locale.ROOT, "｜相对买入持有 %+.2f 个百分点", diff));
        }

        text.append("｜交易 ").append(report.get("num_trades")).append(" 笔");

        Map<String, Object> range = asMap(report.get("range"));
        if (range != null) {
            text.append("（区间 ").append(range.get("start")).append(" ~ ").append(range.get("end"))
                    .append("，").append(range.get("bars")).append(" 根K线）");
        }
        return text.toString();
    }

    /**
     * 产物**引用**：只放标量与指纹，不放 {@code equity_curve} 与 {@code trades}。
     *
     * <p>{@code equity_curve} 最多 5000 个点、{@code trades} 最多 20 笔，
     * 整份报告塞进 {@code artifacts_json}（{@code VARCHAR(4000)}）会被内核截断成
     * 无法解析的垃圾。这里存"这次回测是哪个市场、哪段区间、哪份数据指纹"，
     * 需要曲线时按指纹重跑即可复现。</p>
     */
    private static Map<String, Object> artifacts(Map<String, Object> report,
                                                 String market, int shortMa, int longMa) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("as_of", report.get("as_of"));
        out.put("strategy_version", report.get("strategy_version"));
        out.put("data_fingerprint", report.get("data_fingerprint"));
        out.put("range", report.get("range"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("short_ma", shortMa);
        params.put("long_ma", longMa);
        out.put("params", params);

        out.put("final_equity", report.get("final_equity"));
        out.put("total_return_pct", report.get("total_return_pct"));
        out.put("annual_return_pct", report.get("annual_return_pct"));
        out.put("buy_hold_return_pct", report.get("buy_hold_return_pct"));
        out.put("max_drawdown_pct", report.get("max_drawdown_pct"));
        out.put("num_trades", report.get("num_trades"));
        return out;
    }

    private BacktestParams parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new BacktestParams();
        }
        try {
            BacktestParams parsed = objectMapper.readValue(paramsJson, BacktestParams.class);
            return parsed == null ? new BacktestParams() : parsed;
        } catch (Exception invalid) {
            // 与 MarketScanExecutor 同一处理：参数脏不该让任务失败，用默认值跑完并留下告警更有用。
            log.warn("回测参数解析失败，改用默认值。params={}", paramsJson);
            return new BacktestParams();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String pct(Object value) {
        Double number = asDouble(value);
        return number == null ? "—" : String.format(Locale.ROOT, "%.2f%%", number);
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
    public static class BacktestParams {
        private String market;
        private Integer shortMa;
        private Integer longMa;
        private Double initialCash;
        private Integer limit;
        private String asOf;
    }
}
