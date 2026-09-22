package com.jarvis.research.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 一次 Agent 运行绑定的研究对象。创建后不再随前端页面选择变化。 */
public record AgentResearchContext(String market, String symbol, String name) {

    private static final Set<String> SUPPORTED_MARKETS = Set.of(
            "gold_etf", "london_gold", "a_share", "us_stock", "crypto", "global_index",
            "sge_gold", "jd_gold");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9._:^=\\-]{1,64}");
    public static final AgentResearchContext DEFAULT =
            new AgentResearchContext("gold_etf", "sh518850", "黄金ETF华夏");

    public static AgentResearchContext from(Object raw) {
        if (raw == null) return DEFAULT;
        if (!(raw instanceof Map<?, ?> map)) throw invalid("研究对象格式不正确");
        String market = required(map.get("market"), 32, "研究市场").toLowerCase(Locale.ROOT);
        String symbol = required(map.get("symbol"), 64, "研究对象代码");
        String name = optional(map.get("name"), 120, "研究对象名称");
        if (!SUPPORTED_MARKETS.contains(market)) throw invalid("不支持的研究市场: " + market);
        if (!SYMBOL.matcher(symbol).matches()) throw invalid("研究对象代码格式不正确");
        if (name.isBlank()) name = symbol;
        return new AgentResearchContext(market, symbol, name);
    }

    public static AgentResearchContext persisted(String market, String symbol, String name) {
        if (market == null || symbol == null) return DEFAULT;
        return from(Map.of("market", market, "symbol", symbol, "name", name == null ? symbol : name));
    }

    public String key() {
        return market + ":" + symbol;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("market", market);
        value.put("symbol", symbol);
        value.put("name", name);
        value.put("key", key());
        return value;
    }

    public boolean isCoreGoldMarket() {
        return "gold_etf".equals(market) || "london_gold".equals(market);
    }

    public boolean isExtendedMarket() {
        return Set.of("a_share", "us_stock", "crypto", "global_index").contains(market);
    }

    public boolean supportsExtendedKline() {
        return Set.of("a_share", "us_stock", "crypto").contains(market);
    }

    /** 最终结论至少要出现服务端绑定的名称或证券代码，避免跨标的答案被误展示。 */
    public boolean matches(String content) {
        if (content == null || content.isBlank()) return false;
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.contains(symbol.toLowerCase(Locale.ROOT))
                || (!name.isBlank() && normalized.contains(name.toLowerCase(Locale.ROOT)));
    }

    private static String required(Object value, int max, String label) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw invalid(label + "不能为空");
        if (text.length() > max) throw invalid(label + "长度不能超过 " + max + " 个字符");
        return text;
    }

    private static String optional(Object value, int max, String label) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.length() > max) throw invalid(label + "长度不能超过 " + max + " 个字符");
        return text;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
