package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.service.BacktestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回测执行器的参数翻译与结果整形。
 *
 * <p>两条关键断言：①参数必须原样交给 {@link BacktestService}（口径只留在那一处，
 * 执行器不做任何重算）；②产物里<strong>不能含 equity_curve</strong> —— 最多 5000 个点，
 * 塞进 {@code artifacts_json}（{@code VARCHAR(4000)}）会被截断成无法解析的垃圾。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestExecutorTest {

    @Mock
    private BacktestService backtestService;

    private BacktestExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BacktestExecutor(backtestService, new ObjectMapper());
        when(backtestService.run(anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), any()))
                .thenReturn(report());
    }

    @Test
    void claimsTheBacktestType() {
        assertEquals(ScheduledTaskType.BACKTEST, executor.type());
    }

    @Test
    void fallsBackToDocumentedDefaults() {
        executor.execute(task("{}"));

        verify(backtestService).run(eq("gold_etf"), eq(5), eq(20), eq(100_000.0), eq(120), isNull());
    }

    @Test
    void treatsABlankParamsColumnLikeNoParams() {
        executor.execute(task(null));

        verify(backtestService).run(eq("gold_etf"), eq(5), eq(20), eq(100_000.0), eq(120), isNull());
    }

    @Test
    void passesConfiguredParamsStraightThrough() {
        executor.execute(task("""
                {"market":"london_gold","shortMa":10,"longMa":60,
                 "initialCash":50000,"limit":200,"asOf":"2026-09-16"}"""));

        verify(backtestService).run(eq("london_gold"), eq(10), eq(60), eq(50_000.0), eq(200), eq("2026-09-16"));
    }

    @Test
    void summaryCarriesTheHeadlineMetrics() {
        TaskExecutionResult result = executor.execute(task("{}"));

        String summary = result.summary();
        assertTrue(summary.contains("总收益"), summary);
        assertTrue(summary.contains("年化"), summary);
        assertTrue(summary.contains("最大回撤"), summary);
        assertTrue(summary.contains("交易 6 笔"), summary);
        assertTrue(summary.contains("2025-03-01 ~ 2025-09-16"), summary);
        // 定时任务的价值就是"不用点开也能看出这次跑出什么"，所以策略与买入持有的对比要在摘要里。
        assertTrue(summary.contains("相对买入持有"), summary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void artifactsKeepTheFingerprintButNeverTheEquityCurve() throws Exception {
        TaskExecutionResult result = executor.execute(task("{}"));

        String artifacts = result.artifactsJson();
        Map<String, Object> parsed = new ObjectMapper().readValue(artifacts, Map.class);

        assertEquals("sha256:deadbeef", parsed.get("data_fingerprint"));
        assertEquals(12.34, parsed.get("total_return_pct"));
        assertEquals(6, parsed.get("num_trades"));
        assertTrue(parsed.containsKey("range"));
        // 按 key 判而不是按子串判：`num_trades` 里就含 "trades"，子串断言会误判。
        assertFalse(parsed.containsKey("equity_curve"),
                "整条曲线必须留在报告里，塞进 VARCHAR(4000) 的产物字段会被截断成坏 JSON");
        assertFalse(parsed.containsKey("trades"));
        assertTrue(artifacts.length() < 4000, "产物引用本身必须远小于列宽，实际 " + artifacts.length());
    }

    @Test
    void aLimitSmallerThanTheLongWindowIsRejectedBeforeCallingBacktest() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(task("{\"longMa\":250,\"limit\":120}")));

        assertTrue(e.getMessage().contains("limit"), e.getMessage());
        verify(backtestService, never()).run(anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), any());
    }

    @Test
    void dirtyParamsFallBackToDefaultsInsteadOfFailingTheTask() {
        // 与 MarketScanExecutor 一致：参数脏值得留一条告警并跑完，而不是让任务在历史里变成一次失败。
        executor.execute(task("{不是 JSON"));

        verify(backtestService).run(eq("gold_etf"), eq(5), eq(20), eq(100_000.0), eq(120), isNull());
    }

    @Test
    void backtestFailuresPropagateSoTheKernelCanRecordThem() {
        when(backtestService.run(anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), any()))
                .thenThrow(new IllegalArgumentException("K线数据不足，至少需要 20 根"));

        // 执行器的约定是"失败就抛异常"，内核据此记 FAILED + 异常类型。
        assertThrows(IllegalArgumentException.class, () -> executor.execute(task("{}")));
    }

    @Test
    void anEmptyReportIsTreatedAsAFailure() {
        when(backtestService.run(anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), any()))
                .thenReturn(null);

        // 返回一个"内容写着失败"的结果会被内核当成成功，所以这里必须抛。
        assertThrows(IllegalStateException.class, () -> executor.execute(task("{}")));
    }

    private static ScheduledTask task(String paramsJson) {
        return ScheduledTask.builder()
                .id(7L)
                .userId(42L)
                .name("每日回测")
                .taskType(ScheduledTaskType.BACKTEST)
                .cronExpr("0 30 15 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson(paramsJson)
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .build();
    }

    /** 与 {@code BacktestService.run} 的返回结构一致（含一条会撑爆列宽的 equity_curve）。 */
    private static Map<String, Object> report() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", "2025-03-01");
        range.put("end", "2025-09-16");
        range.put("bars", 120);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", "gold_etf");
        out.put("range", range);
        out.put("as_of", "2025-09-16");
        out.put("strategy_version", "double-ma-v1");
        out.put("data_fingerprint", "sha256:deadbeef");
        out.put("initial_cash", 100_000.0);
        out.put("final_equity", 112_340.0);
        out.put("total_return_pct", 12.34);
        out.put("annual_return_pct", 8.1);
        out.put("buy_hold_return_pct", 10.24);
        out.put("max_drawdown_pct", 6.2);
        out.put("num_trades", 6);
        out.put("trades", List.of(Map.of("date", "2025-04-01", "type", "BUY")));
        out.put("equity_curve", List.of(Map.of("date", "2025-03-01", "equity", 100_000.0)));
        return out;
    }
}
