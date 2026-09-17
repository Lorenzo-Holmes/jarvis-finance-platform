package com.jarvis.research.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 ⑧：模拟盘组合风险指标。
 *
 * <p>期望值全部独立手算，且刻意覆盖三条风险档与两类数据质量分支。
 * 同一组字面量在 {@code backend/tests/test_portfolio_metrics_contract.py} 里由 Python 侧再钉一遍。
 */
class PortfolioMetricsTest {

    private static Map<String, Object> position(Object quantity, Object price, Object loan,
                                                Object margin, Object stale, Object quoteTime) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("quantity", quantity);
        map.put("currentPrice", price);
        map.put("loan", loan);
        map.put("marginUsed", margin);
        if (stale != null) {
            map.put("stale", stale);
        }
        if (quoteTime != null) {
            map.put("quoteTime", quoteTime);
        }
        return map;
    }

    private static Map<String, Object> payload(Object cash, Object initialCash, Object loanBalance,
                                               Object frozenMargin, Map<String, Object> positions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cash", cash);
        map.put("initialCash", initialCash);
        map.put("loanBalance", loanBalance);
        map.put("frozenMargin", frozenMargin);
        map.put("positions", positions);
        return map;
    }

    private static final List<String> RESULT_KEYS = List.of("available", "account_status", "cash",
            "initial_cash", "gross_exposure", "total_assets", "loan_balance", "position_loan_sum",
            "frozen_margin", "position_margin_sum", "net_equity", "gross_leverage",
            "maintenance_margin_pct", "total_return_pct", "risk_status",
            "max_position_concentration_pct", "stale_position_count", "accounting_invariant_ok",
            "data_quality_status", "positions");

    @Test
    @DisplayName("空仓：维持担保比例取 100，集中度为 null（不是 0），风险档为 NONE")
    void emptyPortfolio() {
        Map<String, Object> result = PortfolioMetrics.compute(
                payload(10000, 10000, 0, 0, new LinkedHashMap<>()));

        assertEquals(RESULT_KEYS, new ArrayList<>(result.keySet()));
        assertEquals(true, result.get("available"));
        assertEquals("10000.000000", result.get("cash"));
        assertEquals("0.000000", result.get("gross_exposure"));
        assertEquals("10000.000000", result.get("total_assets"));
        assertEquals("10000.000000", result.get("net_equity"));
        // gross_leverage = gross_exposure / net_equity（空仓时为 0，不是 1）
        assertEquals("0.0000", result.get("gross_leverage"));
        assertEquals("100.0000", result.get("maintenance_margin_pct"));
        assertEquals("0.0000", result.get("total_return_pct"));
        assertEquals("NONE", result.get("risk_status"));
        assertEquals(null, result.get("max_position_concentration_pct"));
        assertEquals(0, result.get("stale_position_count"));
        assertEquals(true, result.get("accounting_invariant_ok"));
        assertEquals("OK", result.get("data_quality_status"));
        assertEquals(List.of(), result.get("positions"));
    }

    @Test
    @DisplayName("单仓：集中度由格式化后的字符串回解析得出，风险档 SAFE")
    void singlePosition() {
        Map<String, Object> positions = new LinkedHashMap<>();
        positions.put("BTC", position(2, 100, 0, 50, null, "2026-01-02T10:00:00"));

        Map<String, Object> result = PortfolioMetrics.compute(
                payload(1000, 1000, 0, 50, positions));

        assertEquals("200.000000", result.get("gross_exposure"));
        assertEquals("1200.000000", result.get("total_assets"));
        assertEquals("1200.000000", result.get("net_equity"));
        assertEquals("600.0000", result.get("maintenance_margin_pct"));   // 1200/200*100
        assertEquals("0.1667", result.get("gross_leverage"));             // 200/1200
        assertEquals("20.0000", result.get("total_return_pct"));          // (1200-1000)/1000
        assertEquals("SAFE", result.get("risk_status"));
        assertEquals("100.0000", result.get("max_position_concentration_pct"));
        assertEquals(true, result.get("accounting_invariant_ok"));        // 冻结保证金与持仓保证金之和相符
        assertEquals("OK", result.get("data_quality_status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("positions");
        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals(List.of("symbol", "quantity", "current_price", "exposure", "loan",
                "margin_used", "unrealized_pnl", "return_on_equity_pct", "quote_time", "stale",
                "concentration_pct"), new ArrayList<>(item.keySet()));
        assertEquals("BTC", item.get("symbol"));
        assertEquals("2.000000", item.get("quantity"));
        assertEquals("100.000000", item.get("current_price"));
        assertEquals("200.000000", item.get("exposure"));
        assertEquals("0.000000", item.get("loan"));
        assertEquals("50.000000", item.get("margin_used"));
        assertEquals("150.000000", item.get("unrealized_pnl"));          // 200 - (0 + 50)
        assertEquals("300.0000", item.get("return_on_equity_pct"));      // 150/50*100
        assertEquals("2026-01-02T10:00:00", item.get("quote_time"));
        assertEquals(false, item.get("stale"));
        assertEquals("100.0000", item.get("concentration_pct"));
    }

    @Test
    @DisplayName("危险档：维持担保比例低于 15；过期报价优先于账务校验；初始资金为 0 时收益率为 null")
    void dangerAndStalePrecedence() {
        Map<String, Object> positions = new LinkedHashMap<>();
        // stale 用字符串 "false"：Python 真值语义下非空字符串为真，不能按布尔解析
        positions.put("X", position(1, 200, 190, 10, "false", null));

        Map<String, Object> result = PortfolioMetrics.compute(
                payload(0, 0, 190, 10, positions));

        assertEquals("10.000000", result.get("net_equity"));              // 0 + 200 - 190
        assertEquals("5.0000", result.get("maintenance_margin_pct"));     // 10/200*100
        assertEquals("20.0000", result.get("gross_leverage"));            // 200/10
        assertEquals("DANGER", result.get("risk_status"));
        assertEquals(null, result.get("total_return_pct"));               // initialCash 非正 → null
        assertEquals(1, result.get("stale_position_count"));
        assertEquals("STALE_QUOTES", result.get("data_quality_status"));
        assertEquals(true, result.get("accounting_invariant_ok"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("positions");
        assertEquals(true, items.get(0).get("stale"), "字符串 false 在 Python 真值语义下为真");
    }

    @Test
    @DisplayName("警戒档：维持担保比例介于 15 与 25 之间")
    void warnBand() {
        Map<String, Object> positions = new LinkedHashMap<>();
        positions.put("X", position(1, 200, 160, 40, null, null));

        Map<String, Object> result = PortfolioMetrics.compute(
                payload(0, 1000, 160, 40, positions));

        assertEquals("40.000000", result.get("net_equity"));
        assertEquals("20.0000", result.get("maintenance_margin_pct"));
        assertEquals("WARN", result.get("risk_status"));
    }

    @Test
    @DisplayName("账务不平：冻结保证金与持仓保证金之和相差超过容差")
    void accountingMismatch() {
        Map<String, Object> positions = new LinkedHashMap<>();
        positions.put("X", position(1, 200, 0, 50, null, null));

        Map<String, Object> result = PortfolioMetrics.compute(
                payload(1000, 1000, 0, 0, positions));   // frozenMargin 0，但持仓保证金合计 50

        assertEquals(false, result.get("accounting_invariant_ok"));
        assertEquals("ACCOUNTING_MISMATCH", result.get("data_quality_status"));
    }

    @Test
    @DisplayName("非对象条目跳过、非数值按 0、payload 非对象时不可用")
    void defensiveBranches() {
        assertEquals(List.of("available", "reason"), new ArrayList<>(
                PortfolioMetrics.compute(null).keySet()));
        assertEquals("no_portfolio_data", PortfolioMetrics.compute(null).get("reason"));

        Map<String, Object> positions = new LinkedHashMap<>();
        positions.put("X", "不是对象");
        positions.put("Y", position("abc", null, null, null, null, null));

        Map<String, Object> result = PortfolioMetrics.compute(payload(100, 100, 0, 0, positions));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("positions");
        assertEquals(1, items.size(), "非对象条目应被跳过，Y 仍在但数值按 0");
        assertEquals("Y", items.get(0).get("symbol"));
        assertEquals("0.000000", result.get("gross_exposure"));
        assertEquals(null, items.get(0).get("return_on_equity_pct"));   // margin<=0 → null
        assertTrue(result.get("positions") instanceof List);
    }
}