package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.service.SimTradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风险检测执行器的行为断言。
 *
 * <p>核心是两条边界：</p>
 * <ul>
 *   <li><b>只读</b>：这里只能调 {@code getAccountOverview}（读），绝不能碰任何会改账户状态的入口
 *       —— 用户建的任务若能触发强平，一个用户就能影响别人的账户。</li>
 *   <li><b>没开通模拟盘 ≠ 任务失败</b>：那是"这次没有可体检的对象"，判失败会让任务
 *       在 5 次之后被自动暂停，而用户完全不知道为什么。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskCheckExecutorTest {

    @Mock
    private SimTradeService simTradeService;

    private RiskCheckExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RiskCheckExecutor(simTradeService, new ObjectMapper());
    }

    @Test
    void claimsTheRiskCheckType() {
        assertEquals(ScheduledTaskType.RISK_CHECK, executor.type());
    }

    @Test
    void readsOnlyTheAccountOverview() {
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("SAFE", 320.15, positions("sh518850", "hf_XAU")));

        executor.execute(task("{}"));

        verify(simTradeService).getAccountOverview(42L);
        // 只读的证明：Mockito 只允许对本类声明的依赖做校验，而 SimTradeService 上唯一的调用就是它。
        verify(simTradeService, org.mockito.Mockito.only()).getAccountOverview(anyLong());
    }

    @Test
    void aHealthyAccountIsReportedAsPassing() {
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("SAFE", 320.15, positions("sh518850", "hf_XAU")));

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("风险检测通过"), summary);
        assertTrue(summary.contains("320.15%"), summary);
        assertTrue(summary.contains("持仓 2 个"), summary);
    }

    @Test
    void anAccountBelowTheEngineThresholdIsReportedAsAHit() {
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("DANGER", 12.5, positions("sh518850", "hf_XAU")));

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("风险检测命中"), summary);
        assertTrue(summary.contains("DANGER"), summary);
        assertTrue(summary.contains("12.50%"), summary);
    }

    @Test
    void aCustomThresholdCanBeStricterThanTheEngineOne() {
        // 引擎判 SAFE（30% > 25%），但用户自己要求 "低于 40% 就提醒我"。
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("SAFE", 30.0, positions("sh518850")));

        String summary = executor.execute(task("{\"warnBelowPct\":40}")).summary();

        assertTrue(summary.contains("风险检测命中"), summary);
        assertTrue(summary.contains("40.00%"), summary);
    }

    @Test
    void theHitSummaryNamesTheWorstPosition() {
        Map<String, Object> positions = new LinkedHashMap<>();
        positions.put("sh518850", position(-1.5, false));
        positions.put("hf_XAU", position(-18.75, false));
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("WARN", 20.0, positions));

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("hf_XAU"), "命中时要指出该看哪个持仓，实际：" + summary);
        assertTrue(summary.contains("-18.75%"), summary);
    }

    @Test
    void anAccountWithoutPositionsIsReportedAsNothingToWatch() {
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("NONE", 100.0, Map.of()));

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("无持仓"), summary);
    }

    @Test
    void anAccountWithoutASimAccountIsNotATaskFailure() {
        when(simTradeService.getAccountOverview(42L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "模拟账户不存在"));

        TaskExecutionResult result = executor.execute(task("{}"));

        assertTrue(result.summary().contains("尚未开通模拟盘"), result.summary());
        // 判失败的话，这类任务会在 5 次之后自动暂停，而原因只是"你没开通模拟盘"。
    }

    @Test
    void otherUpstreamErrorsStillFailTheTask() {
        when(simTradeService.getAccountOverview(42L))
                .thenThrow(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "数据库不可用"));

        assertThrows(ResponseStatusException.class, () -> executor.execute(task("{}")));
    }

    @Test
    void anOutOfRangeThresholdIsRejectedBeforeReadingTheAccount() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(task("{\"warnBelowPct\":150}")));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(task("{\"warnBelowPct\":0}")));

        verify(simTradeService, never()).getAccountOverview(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void artifactsCapThePositionListSoTheyCannotBlowTheColumnWidth() throws Exception {
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 15; i++) {
            many.put("sym" + i, position(-i, false));
        }
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("WARN", 20.0, many));

        String artifacts = executor.execute(task("{}")).artifactsJson();
        Map<String, Object> parsed = new ObjectMapper().readValue(artifacts, Map.class);

        assertEquals(10, ((List<?>) parsed.get("positions")).size());
        assertEquals(Boolean.TRUE, parsed.get("positionsTruncated"));
        assertEquals("WARN", parsed.get("riskStatus"));
        assertEquals(20.0, parsed.get("maintMarginPct"));
        assertTrue(artifacts.length() < 4000, "产物必须小于列宽，实际 " + artifacts.length());
    }

    @Test
    void dirtyParamsFallBackToTheEngineThreshold() {
        when(simTradeService.getAccountOverview(42L)).thenReturn(account("SAFE", 320.15, positions("sh518850")));

        String summary = executor.execute(task("{不是 JSON")).summary();

        assertTrue(summary.contains("25.00%"), "应当回落到 SimRiskService.WARN_MAINT_PCT，实际：" + summary);
    }

    private static ScheduledTask task(String paramsJson) {
        return ScheduledTask.builder()
                .id(9L)
                .userId(42L)
                .name("每日风险检测")
                .taskType(ScheduledTaskType.RISK_CHECK)
                .cronExpr("0 0 21 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson(paramsJson)
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .build();
    }

    private static Map<String, Object> account(String riskStatus, double maintPct,
                                               Map<String, Object> positions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("riskStatus", riskStatus);
        out.put("maintMarginPct", maintPct);
        out.put("netEquity", 12_345.6789);
        out.put("marketValue", 50_000.0);
        out.put("cash", 3_000.0);
        out.put("totalReturnPct", 5.5);
        out.put("status", "ACTIVE");
        out.put("positions", positions);
        return out;
    }

    private static Map<String, Object> positions(String... symbols) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String symbol : symbols) {
            out.put(symbol, position(-1.0, false));
        }
        return out;
    }

    private static Map<String, Object> position(double profitPct, boolean stale) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("symbol", "x");
        detail.put("quantity", 100);
        detail.put("marketValue", 12_345.6789);
        detail.put("leverage", 2);
        detail.put("loan", 3_000.0);
        detail.put("marginUsed", 2_000.0);
        detail.put("profit", -123.45);
        detail.put("profitPct", profitPct);
        detail.put("returnOnEquity", profitPct * 2);
        detail.put("quoteTime", "2026-09-17T14:00:00");
        detail.put("stale", stale);
        return detail;
    }
}
