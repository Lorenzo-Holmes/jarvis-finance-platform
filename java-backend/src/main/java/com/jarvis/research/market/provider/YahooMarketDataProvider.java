package com.jarvis.research.market.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Yahoo Finance Provider。
 *
 * <p>从 {@code MarketDataService#fetchYahooGoldRealtime()} 迁移而来：腾讯伦敦金实时行情故障时，
 * 用 Yahoo 的 COMEX 黄金期货 {@code GC=F} 作为降级报价（期货替代现货，仅供研究参考）。
 * 报价的来源归属键与既有一致（伦敦金 {@code core.yahoo.gold-futures}，美股 {@code extended.yahoo.stock}），
 * 避免重构改掉运维已经依赖的指标名。</p>
 *
 * <p>本 Provider 是降级源（{@link #priority()} = 20），且只提供实时行情，K 线仍由其它 Provider 负责。</p>
 */
@Slf4j
@Component
public class YahooMarketDataProvider implements MarketDataProvider {

    /** 原实现固定返回的期货代码，不随入参 symbol 变化。 */
    private static final String GOLD_SYMBOL = "GC=F";

    private static final String GOLD_CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/GC=F?range=1d&interval=1m";

    private static final ZoneId QUOTE_ZONE = ZoneId.of("Asia/Shanghai");

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public YahooMarketDataProvider() {
        this.webClient = ExternalWebClients.create(Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "Yahoo";
    }

    /**
     * 只声明**真正能取数**的市场。
     *
     * <p>{@code us_stock} 的 Yahoo 报价逻辑尚未迁移——{@link #quote} 对权益类标的会直接返回
     * error，因此这里不能声明支持它。声明支持却必然失败不是"取不到数"这么轻：注册表会把
     * 它选进该市场的链里，调用方据此以为该市场可服务，而熔断器还会为
     * {@code extended.yahoo.stock} 记下一次永远不该发生的失败。</p>
     *
     * <p>{@code sourceKey("us_stock")} 仍返回 {@code extended.yahoo.stock}：
     * 那是扩展行情服务既有的熔断键，与"本 Provider 是否具备该能力"是两件事。</p>
     */
    @Override
    public boolean supports(String market) {
        return "london_gold".equalsIgnoreCase(market);
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public String sourceKey(String market) {
        String normalized = market == null ? "" : market.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "london_gold" -> "core.yahoo.gold-futures";
            case "us_stock" -> "extended.yahoo.stock";
            default -> MarketDataProvider.super.sourceKey(market);
        };
    }

    @Override
    public String displayName() {
        return "Yahoo Finance (GC=F 期货)";
    }

    @Override
    public boolean supportsKline(String market) {
        return false;
    }

    /**
     * 迁移自 {@code fetchYahooGoldRealtime()}：解析 {@code chart.result[0].meta}，
     * 用 {@code regularMarketPrice} 作为现价、{@code previousClose}（缺失时回退
     * {@code chartPreviousClose}）作为昨收。原实现价格为空时抛异常，这里改为返回 error Map。
     */
    @Override
    public Map<String, Object> quote(String symbol) {
        if (!acceptsQuoteSymbol(symbol)) {
            // 美股的 Yahoo 报价逻辑不在本次迁移范围内，宁可显式失败也不要返回错标的的数据。
            log.warn("Yahoo Provider 未迁移该标的的实时行情: symbol={}", symbol);
            return Map.of("error", "Yahoo 未迁移该标的的实时行情: " + symbol);
        }
        try {
            String body = webClient.get()
                    .uri(GOLD_CHART_URL)
                    .retrieve().bodyToMono(String.class).block();
            if (body == null || body.isBlank()) {
                return Map.of("error", "Yahoo 黄金报价为空");
            }
            JsonNode result = objectMapper.readTree(body).path("chart").path("result").get(0);
            if (result == null || result.isMissingNode()) {
                return Map.of("error", "Yahoo 黄金报价为空");
            }
            JsonNode meta = result.path("meta");
            double price = meta.path("regularMarketPrice").asDouble(0.0);
            double previous = meta.path("previousClose")
                    .asDouble(meta.path("chartPreviousClose").asDouble(0.0));
            if (price <= 0) {
                return Map.of("error", "Yahoo 黄金价格为空");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", GOLD_SYMBOL);
            out.put("price", price);
            out.put("prev_close", previous);
            out.put("change", price - previous);
            out.put("change_pct", previous == 0 ? 0.0 : (price - previous) / previous * 100.0);
            if (meta.path("regularMarketTime").isNumber()) {
                out.put("source_quote_time", LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(meta.path("regularMarketTime").asLong()), QUOTE_ZONE));
            }
            return out;
        } catch (Exception e) {
            log.warn("Yahoo 黄金报价失败: {}", e.getMessage());
            return Map.of("error", "Yahoo 黄金报价失败: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        // K 线暂不由本 Provider 承接（见 supportsKline），保持接口完整。
        return List.of();
    }

    /**
     * 标的闸门：本 Provider 只承接黄金类标的。
     *
     * <p>原实现完全忽略入参 symbol；这里只对黄金类标的走同一路径，其余标的显式报错。</p>
     *
     * <p>包级可见**仅供测试**，用于断言不变量「{@code supports(market)} 为 true 的市场，
     * 其真实调用 symbol 必须能过这道闸门」。没有这条断言，声明与能力就可能再次悄悄分叉。</p>
     */
    boolean acceptsQuoteSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return true;
        }
        String normalized = symbol.trim().toLowerCase(Locale.ROOT);
        return "gc=f".equals(normalized)
                || "hf_xau".equals(normalized)
                || "xau".equals(normalized)
                || "london_gold".equals(normalized);
    }
}