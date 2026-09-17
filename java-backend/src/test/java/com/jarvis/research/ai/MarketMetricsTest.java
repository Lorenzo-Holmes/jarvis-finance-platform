package com.jarvis.research.ai;

import com.jarvis.research.market.dto.KlineBarDTO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MarketMetrics} 的行为。
 *
 * <p>期望值一律**独立推导**出来写进断言（有的用闭式解，如 {@code 0.1*sqrt(504)}），
 * 而不是把代码跑出来的数字抄一遍——抄一遍只能证明"还是那个数"，
 * 证明不了它是对的。</p>
 */
class MarketMetricsTest {

    @Test
    void latestCloseAndChanges() {
        List<KlineBarDTO> bars = closes(100, 110, 99, 121);

        assertEquals(121.0, MarketMetrics.latestClose(bars));
        // 最近两根：99 -> 121
        assertEquals(22.0 / 99.0 * 100.0, MarketMetrics.changePct(bars), 1e-12);
        // 区间首尾：100 -> 121
        assertEquals(21.0, MarketMetrics.periodChangePct(bars), 1e-12);
    }

    @Test
    void periodExtremesComeFromHighAndLowNotFromClose() {
        List<KlineBarDTO> bars = List.of(
                bar(100, 105, 95),
                bar(110, 130, 108),
                bar(99, 101, 80));

        assertEquals(130.0, MarketMetrics.periodHigh(bars));
        assertEquals(80.0, MarketMetrics.periodLow(bars));
    }

    @Test
    void movingAverageNeedsAFullWindow() {
        List<KlineBarDTO> bars = closes(100, 110, 99, 121);

        assertEquals(110.0, MarketMetrics.movingAverage(bars, 2), 1e-12, "(99+121)/2");
        assertEquals(107.5, MarketMetrics.movingAverage(bars, 4), 1e-12, "(100+110+99+121)/4");
        assertNull(MarketMetrics.movingAverage(bars, 5), "根数不够就不该硬算");
    }

    @Test
    void movingAverageRejectsANonPositiveWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> MarketMetrics.movingAverage(closes(100, 110), 0));
        assertThrows(IllegalArgumentException.class,
                () -> MarketMetrics.movingAverage(closes(100, 110), -3));
    }

    /** 每日涨幅完全相同 → 样本标准差为 0 → 年化波动率为 0。 */
    @Test
    void volatilityIsZeroWhenReturnsNeverVary() {
        assertEquals(0.0, MarketMetrics.volatility(closes(100, 110, 121)), 1e-12);
    }

    /**
     * 收益率 {+10%, -10%}：均值 0，样本标准差 = 0.1*sqrt(2)（除以 n-1=1），
     * 所以年化 = 0.1*sqrt(2)*sqrt(252) = 0.1*sqrt(504)。这里用闭式解独立推导。
     */
    @Test
    void volatilityAnnualizesTheSampleStandardDeviation() {
        assertEquals(0.1 * Math.sqrt(504), MarketMetrics.volatility(closes(100, 110, 99)), 1e-12);
    }

    @Test
    void volatilityNeedsAtLeastTwoReturns() {
        assertNull(MarketMetrics.volatility(closes(100)), "一根K线没有收益率");
        assertNull(MarketMetrics.volatility(closes(100, 110)), "一根收益率算不出样本标准差");
    }

    @Test
    void maxDrawdownIsNegativeAndMeasuredFromTheRunningPeak() {
        // 100 -> 120（新高）-> 90（自 120 回撤 25%）-> 95
        assertEquals(-25.0, MarketMetrics.maxDrawdownPct(closes(100, 120, 90, 95)), 1e-12);
    }

    @Test
    void maxDrawdownIsZeroWhenTheSeriesOnlyRisesOrStaysFlat() {
        assertEquals(0.0, MarketMetrics.maxDrawdownPct(closes(100, 110, 120)), 1e-12);
        assertEquals(0.0, MarketMetrics.maxDrawdownPct(closes(100, 100, 100)), 1e-12);
    }

    @Test
    void averageVolumeIgnoresBarsWithoutVolume() {
        List<KlineBarDTO> bars = List.of(
                new KlineBarDTO("d1", 1.0, 100.0, 1.0, 1.0, 10.0),
                new KlineBarDTO("d2", 1.0, 110.0, 1.0, 1.0, null),
                new KlineBarDTO("d3", 1.0, 120.0, 1.0, 1.0, 30.0));

        assertEquals(20.0, MarketMetrics.averageVolume(bars), 1e-12, "只平均有量的那两根");
    }

    @Test
    void averageVolumeIsNullWhenNoBarHasVolume() {
        List<KlineBarDTO> bars = List.of(
                new KlineBarDTO("d1", 1.0, 100.0, 1.0, 1.0, null),
                new KlineBarDTO("d2", 1.0, 110.0, 1.0, 1.0, null));

        assertNull(MarketMetrics.averageVolume(bars));
    }

    /** 一行缺收盘价只丢那一行，其余照算——与行情侧的宽松解析一致。 */
    @Test
    void barsWithoutACloseAreSkippedNotFatal() {
        List<KlineBarDTO> bars = List.of(
                new KlineBarDTO("d1", 1.0, 100.0, 1.0, 1.0, 1.0),
                new KlineBarDTO("d2", 1.0, null, 1.0, 1.0, 1.0),
                new KlineBarDTO("d3", 1.0, 120.0, 1.0, 1.0, 1.0));

        assertEquals(120.0, MarketMetrics.latestClose(bars));
        assertEquals(20.0, MarketMetrics.changePct(bars), 1e-12, "100 -> 120，跳过缺价那根");
        assertEquals(2, countUsableForMovingAverage(bars), "可用根数是 2");
    }

    /**
     * 收盘价恰好为 0 的行按"缺值"整行跳过，而不是当成"价格跌到零"。
     *
     * <p>这是本类里最要紧的一条约定：行情源用 0 表示没有数据。若当成真实价格，
     * 一根 {@code 100 -> 0} 会算出一笔 -100% 的收益率，把波动率与回撤污染成垃圾，
     * 而报告里它会看起来像个"结论"。</p>
     */
    @Test
    void aZeroCloseIsTreatedAsMissingDataNotAsAPriceCrash() {
        // 那根 0 被忽略，于是这是 100 -> 110
        assertEquals(10.0, MarketMetrics.changePct(closes(100, 0, 110)), 1e-12,
                "不能算成 -100% 或 Infinity");
        assertEquals(100.0, MarketMetrics.latestClose(closes(100, 0)), "末根缺值就往前取");
        assertNull(MarketMetrics.changePct(closes(0, 100)), "只剩一根，算不出涨跌幅");
        assertNull(MarketMetrics.periodChangePct(closes(0, 100)));

        // 最强的一条：含零价那段的波动率必须与"根本没有那根"完全相同
        assertEquals(MarketMetrics.volatility(closes(100, 110, 120)),
                MarketMetrics.volatility(closes(100, 0, 110, 120)),
                1e-12,
                "零价那根必须被完全忽略，而不是参与计算");
    }

    /** 负价是真实的（2020 年 4 月 WTI 收在负值），不能被当成缺值丢掉。 */
    @Test
    void negativePricesAreRealDataAndAreKept() {
        assertEquals(-10.0, MarketMetrics.latestClose(closes(100, -10)), 1e-12);
        assertEquals(-110.0, MarketMetrics.changePct(closes(100, -10)), 1e-12, "(100 -> -10)");
    }

    @Test
    void emptyOrNullInputYieldsNullForEveryMetric() {
        List<List<KlineBarDTO>> inputs = Arrays.asList(null, List.of());
        for (List<KlineBarDTO> bars : inputs) {
            assertNull(MarketMetrics.latestClose(bars));
            assertNull(MarketMetrics.changePct(bars));
            assertNull(MarketMetrics.periodChangePct(bars));
            assertNull(MarketMetrics.periodHigh(bars));
            assertNull(MarketMetrics.periodLow(bars));
            assertNull(MarketMetrics.averageVolume(bars));
            assertNull(MarketMetrics.movingAverage(bars, 5));
            assertNull(MarketMetrics.volatility(bars));
            assertNull(MarketMetrics.maxDrawdownPct(bars));
        }
    }

    @Test
    void aSingleBarOnlyYieldsTheLevelAndZeroDrawdown() {
        List<KlineBarDTO> bars = closes(100);

        assertEquals(100.0, MarketMetrics.latestClose(bars));
        assertNull(MarketMetrics.changePct(bars));
        assertNull(MarketMetrics.periodChangePct(bars));
        assertEquals(0.0, MarketMetrics.maxDrawdownPct(bars), 1e-12, "一根没有回撤可言");
    }

    // ==================== 夹具 ====================

    /** 只关心收盘价的用例：开高低与量都填成收盘价，避免无关字段影响断言。 */
    private static List<KlineBarDTO> closes(double... values) {
        List<KlineBarDTO> bars = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            bars.add(new KlineBarDTO("2026-09-" + String.format("%02d", i + 1),
                    values[i], values[i], values[i], values[i], 1.0));
        }
        return bars;
    }

    private static KlineBarDTO bar(double close, double high, double low) {
        return new KlineBarDTO("d", close, close, high, low, 1.0);
    }

    private static int countUsableForMovingAverage(List<KlineBarDTO> bars) {
        // 窗口等于可用根数时能算出均值，说明可用根数就是它
        return MarketMetrics.movingAverage(bars, 2) == null ? 1 : 2;
    }
}