package com.jarvis.research.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确定性上下文（Phase 2 ⑧ 统一口径）：chat 面引用的行情 / 指标 / 组合快照。
 *
 * <p>逐字对应 Python 的 {@code research_tools.deterministic_context}：只接受对象入参
 * （否则返回空对象）、prices 与 klines 必须是对象且逐条只处理对象条目、
 * portfolio 缺失或不是对象时给出 {@code no_portfolio_data}。
 *
 * <p><b>本类只做编排，数值一概不在此处计算。</b>每个 section 都转交已移植并被契约
 * 测试钉过的 {@link QuoteMetrics}、{@link KlineMetrics}、{@link PortfolioMetrics}。
 * 一旦本类自己算一遍，就又会出现两套口径——那正是 ⑧ 要消灭的东西。
 */
public final class DeterministicContext {

    private DeterministicContext() {
    }

    public static Map<String, Object> compute(Map<String, Object> rawContext) {
        if (rawContext == null) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated_at", rawContext.get("generated_at"));

        Map<String, Object> quotes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : asMap(rawContext.get("prices")).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw) {
                quotes.put(entry.getKey(), QuoteMetrics.compute(sharedStringKeyed(raw)));
            }
        }
        result.put("quotes", quotes);

        Map<String, Object> indicators = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : asMap(rawContext.get("klines")).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw) {
                indicators.put(entry.getKey(), KlineMetrics.compute(sharedStringKeyed(raw)));
            }
        }
        result.put("indicators", indicators);

        Object portfolio = rawContext.get("portfolio");
        result.put("portfolio", portfolio instanceof Map<?, ?> raw
                ? PortfolioMetrics.compute(sharedStringKeyed(raw))
                : missingPortfolio());
        return result;
    }

    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> raw ? sharedStringKeyed(raw) : new LinkedHashMap<>();
    }

    /** 复用 {@link PortfolioMetrics} 里那份键统一转字符串的实现，避免第三份拷贝。 */
    private static Map<String, Object> sharedStringKeyed(Map<?, ?> raw) {
        return PortfolioMetrics.stringKeyed(raw);
    }

    private static Map<String, Object> missingPortfolio() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("reason", "no_portfolio_data");
        return result;
    }
}