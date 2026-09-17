package com.jarvis.research.ai;

import com.jarvis.research.market.dto.KlineBarDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResearchContextBuilder} 的行为。
 *
 * <p>用 lambda 桩代替行情服务，于是这些断言跑在毫秒级、不需要 Spring 容器，
 * 也没有网络——这正是把 {@link ResearchMarketDataGateway} 抽出来的理由。</p>
 */
class ResearchContextBuilderTest {

    @Test
    void buildsQuoteAndMetricsFromTheGateway() {
        // 25 根是刻意选的：够算 20 日均线，于是"数据齐全、零告警"这条路径真的有覆盖
        List<KlineBarDTO> bars = series(100, 124);
        ResearchContextBuilder builder = new ResearchContextBuilder(new StubGateway()
                .withQuote(Map.of("price", 124.0, "change_pct", 1.5))
                .withBars(bars));

        ResearchContext context = builder.build(task("a_share", "sh600519"));

        assertEquals("a_share", context.market());
        assertEquals("sh600519", context.symbol());
        assertEquals("1d", context.interval());
        assertEquals(25, context.barCount());
        assertEquals(bars.get(0).date(), context.firstBarDate());
        assertEquals(bars.get(24).date(), context.lastBarDate());
        assertEquals(124.0, context.quote().get("price"));
        assertEquals(List.of(), context.warnings(), "数据齐全时不该有告警");
        assertTrue(context.hasPriceData());

        // 指标与 MarketMetrics 同源，这里只钉"装进去了"与键名；数值用独立算式核对
        assertEquals(124.0, context.metrics().get(ResearchContextBuilder.LATEST_CLOSE));
        assertEquals(1.0 / 123.0 * 100.0, (Double) context.metrics().get(ResearchContextBuilder.CHANGE_PCT), 1e-12,
                "123 -> 124，涨了 1 元但只有 0.813%");
        assertEquals(24.0, (Double) context.metrics().get(ResearchContextBuilder.PERIOD_CHANGE_PCT), 1e-12,
                "100 -> 124");
        assertEquals(124.0, context.metrics().get(ResearchContextBuilder.PERIOD_HIGH));
        assertEquals(100.0, context.metrics().get(ResearchContextBuilder.PERIOD_LOW));
        assertEquals(122.0, (Double) context.metrics().get(ResearchContextBuilder.MA5), 1e-12,
                "(120+121+122+123+124)/5");
        assertEquals(114.5, (Double) context.metrics().get(ResearchContextBuilder.MA20), 1e-12,
                "(105+126)/2，即 105..124 的均值");
        assertTrue(context.metrics().containsKey(ResearchContextBuilder.VOLATILITY));
        assertTrue(context.metrics().containsKey(ResearchContextBuilder.MAX_DRAWDOWN_PCT));
    }

    /**
     * 算得出来的键在、算不出来的键**不出现**（而不是出现成 null）。
     *
     * <p>3 根K线正好把两类分开：2 个收益率够算样本标准差，但不够算 5 日均线。</p>
     */
    @Test
    void metricsThatCannotBeComputedAreAbsentRatherThanNull() {
        ResearchContextBuilder builder = new ResearchContextBuilder(
                new StubGateway().withBars(closes(100, 110, 99)));

        Map<String, Object> metrics = builder.build(task("a_share", "sh600519")).metrics();

        assertTrue(metrics.containsKey(ResearchContextBuilder.VOLATILITY),
                "2 个收益率够算样本标准差");
        assertTrue(metrics.containsKey(ResearchContextBuilder.CHANGE_PCT));
        assertFalse(metrics.containsKey(ResearchContextBuilder.MA5), "3 根算不出 5 日均线");
        assertFalse(metrics.containsKey(ResearchContextBuilder.MA20));
        assertFalse(metrics.values().contains(null), "算不出来就不该出现这个键，更不能是 null");
    }

    /** 一根K线：剩得下水平与回撤，涨跌幅与均线都不该出现。 */
    @Test
    void aSingleBarYieldsOnlyTheLevelAndDrawdown() {
        ResearchContextBuilder builder = new ResearchContextBuilder(
                new StubGateway().withBars(closes(100)));

        ResearchContext context = builder.build(task("a_share", "sh600519"));

        assertTrue(context.metrics().containsKey(ResearchContextBuilder.LATEST_CLOSE));
        assertTrue(context.metrics().containsKey(ResearchContextBuilder.MAX_DRAWDOWN_PCT));
        assertFalse(context.metrics().containsKey(ResearchContextBuilder.CHANGE_PCT));
        assertFalse(context.metrics().containsKey(ResearchContextBuilder.PERIOD_CHANGE_PCT));
        assertFalse(context.metrics().containsKey(ResearchContextBuilder.MA5));
        assertTrue(context.warnings().stream().anyMatch(w -> w.contains("部分指标")),
                "根数不足要说清楚，实际: " + context.warnings());
    }

    /**
     * 取不到数据**不抛异常**，只在告警里说明，报告照常生成。
     *
     * <p>这是本类最要紧的一条：让一次行情抖动把整个研究任务变成 FAILED，
     * 是把数据层的问题错误地升级成了业务层的问题。</p>
     */
    @Test
    void missingDataBecomesAWarningNotAFailure() {
        ResearchContextBuilder builder = new ResearchContextBuilder(new StubGateway());

        ResearchContext context = builder.build(task("a_share", "sh600519"));

        assertEquals(0, context.barCount());
        assertNull(context.firstBarDate());
        assertNull(context.lastBarDate());
        assertNull(context.quote());
        assertEquals(Map.of(), context.metrics());
        assertFalse(context.hasPriceData());
        assertTrue(context.warnings().contains("未取到最新报价"));
        assertTrue(context.warnings().contains("未取到日K数据，无法计算技术指标"));
    }

    /** 上游返回 error 信封等同于"没取到"，不能把错误信息当成报价传给模型。 */
    @Test
    void anErrorEnvelopeCountsAsNoQuote() {
        ResearchContextBuilder builder = new ResearchContextBuilder(new StubGateway()
                .withQuote(Map.of("error", "腾讯行情请求失败")));

        ResearchContext context = builder.build(task("a_share", "sh600519"));

        assertNull(context.quote(), "错误信封不能当报价");
        assertTrue(context.warnings().contains("未取到最新报价"));
    }

    /** 网关自己吞掉异常是它的契约；这里验证"返回空"这条路径就够。 */
    @Test
    void anEmptyQuoteMapCountsAsNoQuote() {
        ResearchContextBuilder builder = new ResearchContextBuilder(
                new StubGateway().withQuote(Map.of()));

        assertNull(builder.build(task("a_share", "sh600519")).quote());
    }

    /**
     * 宏观任务没有标的：**一次行情请求都不该发**。
     *
     * <p>取数有成本也有失败面，没有标的就别去问。用计数器断言"没发请求"，
     * 而不是只看结果对不对。</p>
     */
    @Test
    void aMacroTaskWithoutASymbolNeverAsksForMarketData() {
        StubGateway gateway = new StubGateway().withBars(closes(100, 110));
        ResearchContextBuilder builder = new ResearchContextBuilder(gateway);

        ResearchContext context = builder.build(task("a_share", null));

        assertEquals(0, gateway.quoteCalls.get(), "没标的就不该问报价");
        assertEquals(0, gateway.barCalls.get(), "没标的就不该问K线");
        assertEquals(0, context.barCount());
        assertEquals(List.of("该任务没有指定标的，未取行情数据"), context.warnings(),
                "这不是故障，要说得像一句说明");
    }

    @Test
    void aTaskWithoutAMarketIsAlsoTreatedAsMacro() {
        StubGateway gateway = new StubGateway();
        ResearchContextBuilder builder = new ResearchContextBuilder(gateway);

        builder.build(task(null, "sh600519"));

        assertEquals(0, gateway.barCalls.get(), "没有市场就不知道该走哪条降级链");
    }

    /** 取数窗口固定为常量，避免每个调用点各写一个数。 */
    @Test
    void theDailyWindowIsTheDeclaredConstant() {
        StubGateway gateway = new StubGateway().withBars(closes(100, 110));
        new ResearchContextBuilder(gateway).build(task("a_share", "sh600519"));

        assertEquals(ResearchContext.DAILY_BAR_LIMIT, gateway.lastLimit);
    }

    // ==================== 夹具 ====================

    private static ResearchTask task(String market, String symbol) {
        return ResearchTask.builder()
                .userId(1L)
                .title("t")
                .taskType(ResearchTaskType.REPORT)
                .market(market)
                .symbol(symbol)
                .question("q")
                .build();
    }

    private static List<KlineBarDTO> closes(double... values) {
        List<KlineBarDTO> bars = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            bars.add(new KlineBarDTO("2026-09-" + String.format("%02d", i + 1),
                    values[i], values[i], values[i], values[i], 1.0));
        }
        return bars;
    }

    /** 收盘价依次递增的连续序列（开高低都等于收盘价，方便手算）。 */
    private static List<KlineBarDTO> series(int from, int to) {
        double[] values = new double[to - from + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }
        return closes(values);
    }

    private static final class StubGateway implements ResearchMarketDataGateway {

        private Map<String, Object> quote = Map.of();
        private List<KlineBarDTO> bars = List.of();
        private final AtomicInteger quoteCalls = new AtomicInteger();
        private final AtomicInteger barCalls = new AtomicInteger();
        private int lastLimit;

        StubGateway withQuote(Map<String, Object> quote) {
            this.quote = quote;
            return this;
        }

        StubGateway withBars(List<KlineBarDTO> bars) {
            this.bars = bars;
            return this;
        }

        @Override
        public Map<String, Object> quote(String market, String symbol) {
            quoteCalls.incrementAndGet();
            return quote;
        }

        @Override
        public List<KlineBarDTO> dailyBars(String market, String symbol, int limit) {
            barCalls.incrementAndGet();
            lastLimit = limit;
            return bars;
        }
    }
}