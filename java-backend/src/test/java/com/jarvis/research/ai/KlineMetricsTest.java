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
 * Phase 2 ⑧：K 线技术指标。
 *
 * <p>期望值全部**独立手算**，不是从实现输出回抄。同一组字面量在
 * {@code backend/tests/test_kline_metrics_contract.py} 里被 Python 侧独立钉一遍。
 *
 * <p>递增序列的 EMA 可以精确推导：种子是前 12 根均值 6.5，alpha = 2/13，
 * 递推后第 v 根恰好等于 v - 5.5，故末根（v=20）为 14.5——没有舍入歧义，
 * 正好用来验证"种子用均值而非首根"这个最容易写错的点。
 */
class KlineMetricsTest {

    private static Map<String, Object> row(String date, Object close, Object low, Object high) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", date);
        map.put("close", close);
        map.put("low", low);
        map.put("high", high);
        return map;
    }

    private static Map<String, Object> payload(List<Map<String, Object>> rows) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("data", rows);
        return map;
    }

    @Test
    @DisplayName("恒定序列：均线/EMA 等于该常数，RSI 走无波动分支得 50")
    void constantSeries() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(row("d" + i, 100, 95, 105));
        }

        Map<String, Object> result = KlineMetrics.compute(payload(rows));

        assertEquals(List.of("available", "bars", "start", "end", "last_close", "sma5", "sma20",
                "ema12", "rsi14", "distance_to_sma20_pct", "support20", "resistance20"),
                new ArrayList<>(result.keySet()));
        assertEquals(true, result.get("available"));
        assertEquals(20, result.get("bars"));
        assertEquals("d1", result.get("start"));
        assertEquals("d20", result.get("end"));
        assertEquals("100.000000", result.get("last_close"));
        assertEquals("100.000000", result.get("sma5"));
        assertEquals("100.000000", result.get("sma20"));
        assertEquals("100.000000", result.get("ema12"));
        // 涨跌全为 0 → 平均跌幅 0，且平均涨幅也为 0 → Python 走 50 分支
        assertEquals("50.0000", result.get("rsi14"));
        assertEquals("0.0000", result.get("distance_to_sma20_pct"));
        assertEquals("95.000000", result.get("support20"));
        assertEquals("105.000000", result.get("resistance20"));
    }

    @Test
    @DisplayName("严格递增序列：EMA 种子用均值，末根精确等于 14.5；无高低价时支撑压力为 null")
    void increasingSeriesHasExactEma() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(row("d" + i, i, null, null));
        }

        Map<String, Object> result = KlineMetrics.compute(payload(rows));

        assertEquals("20.000000", result.get("last_close"));
        assertEquals("18.000000", result.get("sma5"));       // (16+17+18+19+20)/5 = 90/5
        assertEquals("10.500000", result.get("sma20"));     // (1+…+20)/20 = 210/20
        assertEquals("14.500000", result.get("ema12"));     // 种子 6.5，alpha 2/13 → v-5.5
        assertEquals("100.0000", result.get("rsi14"));      // 全是涨幅
        // (20-10.5)*100/10.5 = 90.476190… → 90.4762
        assertEquals("90.4762", result.get("distance_to_sma20_pct"));
        assertTrue(result.containsKey("support20"));
        assertEquals(null, result.get("support20"));
        assertEquals(null, result.get("resistance20"));
    }

    @Test
    @DisplayName("样本不足 period：各指标为 null（键全部保留），字符串收盘价按字面解析")
    void insufficientBarsYieldNullIndicators() {
        List<Map<String, Object>> rows = List.of(
                row("d1", "100", 95, 105),
                row("d2", "101", 96, 106),
                row("d3", "102", 97, 107));

        Map<String, Object> result = KlineMetrics.compute(payload(rows));

        assertEquals(3, result.get("bars"));
        assertEquals("102.000000", result.get("last_close"));
        assertEquals(null, result.get("sma5"));
        assertEquals(null, result.get("sma20"));
        assertEquals(null, result.get("ema12"));
        assertEquals(null, result.get("rsi14"));
        assertEquals(null, result.get("distance_to_sma20_pct"));
        assertEquals("95.000000", result.get("support20"));
        assertEquals("107.000000", result.get("resistance20"));
    }

    @Test
    @DisplayName("close 不可解析的行被整行丢弃，bars 只计有效行")
    void invalidRowsAreDroppedEntirely() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("d1", 100, 95, 105));
        rows.add(row("d2", null, 96, 106));
        rows.add(row("d3", "abc", 97, 107));
        rows.add(row("d4", 102, 98, 108));

        Map<String, Object> result = KlineMetrics.compute(payload(rows));

        assertEquals(2, result.get("bars"), "只有 d1/d4 是有效行");
        assertEquals("d1", result.get("start"));
        assertEquals("d4", result.get("end"));
        assertEquals("102.000000", result.get("last_close"));
        // 有效行只有 2 根 → 均线类指标全部为 null
        assertEquals(null, result.get("sma5"));
    }

    @Test
    @DisplayName("没有可用 K 线时返回不可用形状（只有 available 与 reason）")
    void unavailableShapes() {
        Map<String, Object> noData = new LinkedHashMap<>();
        assertEquals(List.of("available", "reason"), new ArrayList<>(KlineMetrics.compute(noData).keySet()));
        assertEquals(false, KlineMetrics.compute(noData).get("available"));
        assertEquals("no_kline_data", KlineMetrics.compute(noData).get("reason"));

        Map<String, Object> notAList = new LinkedHashMap<>();
        notAList.put("data", "不是列表");
        assertEquals("no_kline_data", KlineMetrics.compute(notAList).get("reason"));

        assertEquals("no_kline_data", KlineMetrics.compute(null).get("reason"));

        Map<String, Object> allInvalid = payload(List.of(row("d1", "abc", 1, 2)));
        assertEquals("no_kline_data", KlineMetrics.compute(allInvalid).get("reason"));
    }
}