package com.jarvis.research.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险指标迁移的口径契约。
 *
 * <p>期望值都是**独立推导**的闭合解，不是从实现里抄出来的——测试如果只是复述实现，
 * 那它只能证明"代码没变"，证明不了"算对了"。每个用例都在注释里写下推导过程。</p>
 */
class RiskMetricsTest {

    /** 每次翻倍，11 根 → 10 个收益率，且每个都精确等于 1.0（2 倍是精确可表示的）。 */
    private static List<Double> doubling() {
        List<Double> closes = new ArrayList<>();
        double value = 100;
        for (int i = 0; i < 11; i++) {
            closes.add(value);
            value *= 2;
        }
        return closes;
    }

    /** 十根持平 + 最后一根腰斩：9 个 0 收益率 + 1 个 -0.5，便于手算分位数与回撤。 */
    private static List<Double> flatThenCrash() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            closes.add(100.0);
        }
        closes.add(50.0);
        return closes;
    }

    /** 在 100 / 100.5 之间来回：收益率的绝对值都很小，一个阈值都不该命中。 */
    private static List<Double> jitter() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            closes.add(100.0);
            closes.add(100.5);
        }
        return closes.subList(0, 11);
    }

    @Test
    @DisplayName("收益率全等于 1.0 时：VaR 与 ES 都是 100%，波动率为 0，回撤为 0")
    void doublingSeries() {
        RiskMetrics.Result result = RiskMetrics.compute(doubling(), null, null, null);

        assertTrue(result.available());
        assertEquals(11, result.bars());
        // 置信度缺省 0.95；alpha = 0.05；k = max(1, round(10 × 0.05)) = max(1, 1) = 1
        assertEquals("0.950000", result.confidence());
        // 最差分位就是唯一的分位：1.0 → 100%
        assertEquals("100.0000", result.varPct());
        assertEquals("100.0000", result.esPct());
        // 10 个完全相同值的样本标准差 = 0，年化后仍是 0
        assertEquals("0.0000", result.volAnnualPct());
        // 单调上升，回撤从未小于 0
        assertEquals("0.0000", result.maxDrawdownPct());
        assertEquals("102400.000000", result.lastClose());
        assertNull(result.symbol());
        assertNull(result.varAmount());

        // 100% ≥ 4% → var high；100% ≥ 5% → es high；回撤 0 < 20% → 无回撤告警（顺序即阈值顺序）
        assertEquals(2, result.alerts().size());
        assertEquals("high", result.alerts().get(0).level());
        assertEquals("var", result.alerts().get(0).metric());
        assertEquals("单日VaR绝对值 >= 4%", result.alerts().get(0).rule());
        assertEquals("单日最大预期亏损约 100.0000%，风险敞口偏高。", result.alerts().get(0).message());
        assertEquals("es", result.alerts().get(1).metric());
    }

    @Test
    @DisplayName("十根持平后腰斩：VaR/ES 为 -50%，年化波动率 250.9980%，回撤告警命中")
    void flatThenCrashSeries() {
        RiskMetrics.Result result = RiskMetrics.compute(flatThenCrash(), null, null, null);

        assertEquals("-50.0000", result.varPct());
        assertEquals("-50.0000", result.esPct());
        // 手算：收益率 = [0×9, -0.5]，均值 -0.05
        // 离差平方和 = 9×0.05² + 0.45² = 0.0225 + 0.2025 = 0.225
        // 样本方差 = 0.225 / 9 = 0.025 → 样本标准差 = √0.025 ≈ 0.15811388300841897
        // 年化 = ×√252 ≈ 2.5099800796 → ×100 → 250.99800796 → 4 位小数 → 250.9980
        assertEquals("250.9980", result.volAnnualPct());
        assertEquals("-50.0000", result.maxDrawdownPct());
        assertEquals("50.000000", result.lastClose());

        assertEquals(3, result.alerts().size());
        assertEquals("max_drawdown", result.alerts().get(2).metric());
        assertEquals("medium", result.alerts().get(2).level());
        assertEquals("历史最大回撤 >= 20%", result.alerts().get(2).rule());
    }

    @Test
    @DisplayName("样本不足时给出不可用而不是硬算：11 根以下分两种原因")
    void insufficientSamples() {
        // 10 根 → 通过 MIN_BARS，但只有 9 个收益率 < MIN_RETURNS
        RiskMetrics.Result tenBars = RiskMetrics.compute(Arrays.asList(
                1.0, 2, 3, 4, 5, 6, 7, 8, 9, 10), null, null, null);
        assertFalse(tenBars.available());
        assertEquals("insufficient_closes", tenBars.reason());
        assertEquals(10, tenBars.bars());

        // 5 根 → 连 MIN_BARS 都不到
        RiskMetrics.Result fiveBars = RiskMetrics.compute(Arrays.asList(1.0, 2, 3, 4, 5), null, null, null);
        assertFalse(fiveBars.available());
        assertEquals(5, fiveBars.bars());

        // 不可用时的契约只有三个字段，不含任何指标
        assertEquals(List.of("available", "reason", "bars"), new ArrayList<>(fiveBars.toMap().keySet()));
        assertEquals(false, fiveBars.toMap().get("available"));
    }

    @Test
    @DisplayName("刻意保留的差异：输入过滤沿用 > 0，0 与负价都会被丢掉")
    void zeroAndNegativeClosesAreDropped() {
        // 13 个输入里有 1 个 0 和 1 个负价 → 只剩 11 根，刚好够 10 个收益率
        RiskMetrics.Result result = RiskMetrics.compute(Arrays.asList(
                100.0, 110, 0, 90, -5, 120, 130, 140, 150, 160, 170, 180, 190), null, null, null);

        assertTrue(result.available(), "丢掉 0 与负价后仍然够样本");
        assertEquals(11, result.bars(), "13 个输入 - 1 个 0 - 1 个负价 = 11 根");
        // 序列被"缝合"过：90 直接跳到 120，收益率 +33.33%。这与 MarketMetrics 的 != 0 不同，
        // 后者会保留负价（2020 年 4 月 WTI 真的收在负值）。这里先锁住现状，
        // 要不要统一是**一次单独的决定**，因为它改变已产出的数字。
    }

    @Test
    @DisplayName("有账户资金时：var_amount 出现，且金额告警排在阈值告警之后")
    void portfolioAmountAndAlertOrder() {
        RiskMetrics.Result result = RiskMetrics.compute(doubling(), null, 10000, null);

        // 1.0 × 10000 = 10000
        assertEquals("10000.000000", result.varAmount());
        // 原实现先追加阈值告警，再追加金额告警，顺序即契约
        assertEquals(3, result.alerts().size());
        assertEquals("var", result.alerts().get(0).metric());
        assertEquals("es", result.alerts().get(1).metric());
        assertEquals("portfolio_var", result.alerts().get(2).metric());
        assertEquals("medium", result.alerts().get(2).level());
        assertEquals("按资金规模估算，单日潜在亏损约 10000.000000 元，建议评估仓位。",
                result.alerts().get(2).message());
    }

    @Test
    @DisplayName("一个阈值都没命中时补一条 low 告警，而不是给空列表")
    void noThresholdHit() {
        RiskMetrics.Result result = RiskMetrics.compute(jitter(), null, null, null);

        // 收益率在 ±0.5% 附近：|VaR| ≈ 0.4975% < 2%，|ES| < 5%，|回撤| ≈ 0.4975% < 20%
        assertEquals("-0.4975", result.varPct());
        assertEquals(1, result.alerts().size());
        assertEquals("low", result.alerts().get(0).level());
        assertEquals("overall", result.alerts().get(0).metric());
        assertEquals("无阈值命中", result.alerts().get(0).rule());
        assertEquals("当前样本未命中高风险阈值，维持常规监控。", result.alerts().get(0).message());
    }

    @Test
    @DisplayName("未命中阈值但有账户资金时，low 告警里带上按资金估算的金额")
    void noThresholdHitWithPortfolio() {
        RiskMetrics.Result result = RiskMetrics.compute(jitter(), null, 100000, null);

        // 100/100.5 - 1 = -0.00497512437810945273…；×100000 = -497.512437810945… → 取绝对值 6 位小数
        assertEquals("-497.512438", result.varAmount());
        assertEquals(1, result.alerts().size());
        assertEquals("low", result.alerts().get(0).level());
        assertEquals("当前样本未命中高风险阈值；按资金规模估算单日潜在亏损约 497.512438 元。",
                result.alerts().get(0).message());
    }

    @Test
    @DisplayName("置信度缺省 0.95，越界夹到 [0.5, 0.99]")
    void confidenceIsClamped() {
        assertEquals("0.950000", RiskMetrics.compute(doubling(), null, null, null).confidence());
        assertEquals("0.990000", RiskMetrics.compute(doubling(), 2.0, null, null).confidence());
        assertEquals("0.500000", RiskMetrics.compute(doubling(), 0.1, null, null).confidence());
        assertEquals("0.900000", RiskMetrics.compute(doubling(), "0.9", null, null).confidence());
        // 解析不出来时退回缺省，而不是抛异常
        assertEquals("0.950000", RiskMetrics.compute(doubling(), "不是数字", null, null).confidence());
    }

    @Test
    @DisplayName("输出是格式化字符串而不是数字：金额 6 位小数、百分比 4 位小数")
    void outputIsFormattedStrings() {
        Map<String, Object> map = RiskMetrics.compute(doubling(), null, 10000, "sh600519").toMap();

        assertEquals(true, map.get("available"));
        assertEquals("sh600519", map.get("symbol"));
        assertEquals("102400.000000", map.get("last_close"));
        assertEquals("100.0000", map.get("var_pct"));
        assertEquals("0.0000", map.get("max_drawdown_pct"));
        assertEquals("10000.000000", map.get("var_amount"));
        // 字段顺序也与原实现一致，便于逐字比对
        assertEquals(List.of("available", "symbol", "confidence", "bars", "last_close", "var_pct",
                        "es_pct", "vol_annual_pct", "max_drawdown_pct", "alerts", "var_amount"),
                new ArrayList<>(map.keySet()));

        List<?> alerts = (List<?>) map.get("alerts");
        Map<?, ?> first = (Map<?, ?>) alerts.get(0);
        assertEquals(List.of("level", "metric", "rule", "message"), new ArrayList<>(first.keySet()));
    }

    @Test
    @DisplayName("字符串输入按 Decimal(str(v)) 的语义解析，非法值直接丢弃")
    void stringInputsAreParsedLikePython() {
        // 14 个输入：12 个合法数字（全部写成字符串）+ 1 个 "0"（不满足 > 0）+ 1 个无法解析
        RiskMetrics.Result result = RiskMetrics.compute(Arrays.asList(
                "100", "110", "0", "120", "130", "140", "150", "160", "170", "180", "190", "200", "210", "abc"),
                null, null, null);

        assertTrue(result.available());
        assertEquals(12, result.bars(), "14 个输入 - 1 个 0 - 1 个 abc = 12 根");
        assertEquals("210.000000", result.lastClose());
    }

    @Test
    @DisplayName("丢数据会把样本压到门槛以下：过滤不是无副作用的")
    void droppingValuesCanFallBelowTheSampleFloor() {
        // 12 个合法值里有 2 个非法 → 只剩 10 根 → 只有 9 个收益率 < 10 → 判定不可用。
        // 这条说明"过滤掉坏数据"与"够不够样本"是耦合的：丢着丢着就不该再给结论了。
        RiskMetrics.Result result = RiskMetrics.compute(Arrays.asList(
                "100", "110", "0", "120", "130", "140", "150", "160", "170", "180", "190", "abc"),
                null, null, null);

        assertFalse(result.available());
        assertEquals("insufficient_closes", result.reason());
        assertEquals(10, result.bars());
    }
}