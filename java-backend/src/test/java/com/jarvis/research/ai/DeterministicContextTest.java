package com.jarvis.research.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 ⑧：确定性上下文编排。
 *
 * <p>本类不产生新数值，所以测试钉的是**编排契约**：段落形状、键顺序、
 * 非对象条目的处理、缺失段落的表现，以及各段落确实等于对应单元的输出。
 */
class DeterministicContextTest {

    private static Map<String, Object> quoteSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("price", 100.5);
        snapshot.put("prev_close", 100);
        snapshot.put("open", 99);
        snapshot.put("high", 102);
        snapshot.put("low", 98.5);
        snapshot.put("quote_time", "2026-01-02T10:00:00");
        snapshot.put("source", "tencent");
        return snapshot;
    }

    private static Map<String, Object> klinePayload() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("date", "d1", "close", 100, "low", 95, "high", 105));
        rows.add(Map.of("date", "d2", "close", 101, "low", 96, "high", 106));
        rows.add(Map.of("date", "d3", "close", 102, "low", 97, "high", 107));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", rows);
        return payload;
    }

    private static Map<String, Object> emptyPortfolio() {
        Map<String, Object> portfolio = new LinkedHashMap<>();
        portfolio.put("cash", 10000);
        portfolio.put("initialCash", 10000);
        portfolio.put("loanBalance", 0);
        portfolio.put("frozenMargin", 0);
        portfolio.put("positions", new LinkedHashMap<>());
        return portfolio;
    }

    @Test
    @DisplayName("完整上下文：四个段落按序输出，各段落等于对应单元的输出")
    void fullContextComposesEverySection() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generated_at", "2026-01-02T00:00:00");
        raw.put("prices", Map.of("gold", quoteSnapshot()));
        raw.put("klines", Map.of("gold", klinePayload()));
        raw.put("portfolio", emptyPortfolio());

        Map<String, Object> result = DeterministicContext.compute(raw);

        assertEquals(List.of("generated_at", "quotes", "indicators", "portfolio"),
                new ArrayList<>(result.keySet()));
        assertEquals("2026-01-02T00:00:00", result.get("generated_at"));

        @SuppressWarnings("unchecked")
        Map<String, Object> quotes = (Map<String, Object>) result.get("quotes");
        assertEquals(List.of("gold"), new ArrayList<>(quotes.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) quotes.get("gold");
        assertEquals(QuoteMetrics.compute(quoteSnapshot()), quote);
        assertEquals("100.500000", quote.get("price"));
        assertEquals("0.500000", quote.get("change"));

        @SuppressWarnings("unchecked")
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        @SuppressWarnings("unchecked")
        Map<String, Object> kline = (Map<String, Object>) indicators.get("gold");
        assertEquals(KlineMetrics.compute(klinePayload()), kline);
        assertEquals(3, kline.get("bars"));
        assertEquals("102.000000", kline.get("last_close"));
        assertEquals(null, kline.get("sma5"));
        assertEquals("95.000000", kline.get("support20"));

        @SuppressWarnings("unchecked")
        Map<String, Object> portfolio = (Map<String, Object>) result.get("portfolio");
        assertEquals(PortfolioMetrics.compute(emptyPortfolio()), portfolio);
        assertEquals("10000.000000", portfolio.get("net_equity"));
        assertEquals("OK", portfolio.get("data_quality_status"));
    }

    @Test
    @DisplayName("空对象：段落齐全但为空，组合段落给 no_portfolio_data")
    void emptyContextKeepsTheSectionShape() {
        Map<String, Object> result = DeterministicContext.compute(new LinkedHashMap<>());

        assertEquals(List.of("generated_at", "quotes", "indicators", "portfolio"),
                new ArrayList<>(result.keySet()));
        assertEquals(null, result.get("generated_at"));
        assertEquals(new LinkedHashMap<>(), result.get("quotes"));
        assertEquals(new LinkedHashMap<>(), result.get("indicators"));
        assertEquals(Map.of("available", false, "reason", "no_portfolio_data"), result.get("portfolio"));
    }

    @Test
    @DisplayName("入参不是对象时返回空对象（不是带段落的形状）")
    void nonMapInputYieldsEmptyMap() {
        assertEquals(new LinkedHashMap<>(), DeterministicContext.compute(null));
    }

    @Test
    @DisplayName("prices/klines 不是对象时视为空；非对象条目跳过；portfolio 不是对象时给 no_portfolio_data")
    void defensiveBranches() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("prices", "不是对象");
        raw.put("klines", List.of("不是对象"));
        raw.put("portfolio", "不是对象");

        Map<String, Object> result = DeterministicContext.compute(raw);

        assertEquals(new LinkedHashMap<>(), result.get("quotes"));
        assertEquals(new LinkedHashMap<>(), result.get("indicators"));
        assertEquals("no_portfolio_data",
                ((Map<?, ?>) result.get("portfolio")).get("reason"));

        // 段落是对象、但其中某个条目不是对象 → 该条目被跳过
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("prices", Map.of("gold", quoteSnapshot(), "bad", "不是对象"));
        // Map.of 不允许 null，这里用可变 map 以便放入非对象值
        Map<String, Object> prices = new LinkedHashMap<>();
        prices.put("gold", quoteSnapshot());
        prices.put("bad", "不是对象");
        mixed.put("prices", prices);

        @SuppressWarnings("unchecked")
        Map<String, Object> quotes = (Map<String, Object>) DeterministicContext.compute(mixed).get("quotes");
        assertEquals(List.of("gold"), new ArrayList<>(quotes.keySet()));
    }
}