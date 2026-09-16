package com.jarvis.research.market.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EastMoney（东方财富）Provider。
 *
 * <p>从 {@code MarketDataService#fetchEastmoneyRealtime(String)} 迁移而来：作为 A 股 / 黄金 ETF
 * 实时行情的备用源（腾讯故障时的切换目标），{@link #priority()} = 30，排在腾讯（10 段）之后。</p>
 *
 * <p>只提供实时行情，不提供 K 线（K 线仍由其它 Provider 承接）。</p>
 */
@Slf4j
@Component
public class EastMoneyMarketDataProvider implements MarketDataProvider {

    private static final String QUOTE_FIELDS = "f43,f44,f45,f46,f57,f58,f60,f169,f170";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EastMoneyMarketDataProvider() {
        this.webClient = ExternalWebClients.create(Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "EastMoney";
    }

    @Override
    public boolean supports(String market) {
        return "gold_etf".equalsIgnoreCase(market)
                || "a_share".equalsIgnoreCase(market);
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public String sourceKey(String market) {
        String normalized = market == null ? "" : market.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gold_etf" -> "core.eastmoney.etf";
            case "a_share" -> "extended.eastmoney.stock";
            default -> MarketDataProvider.super.sourceKey(market);
        };
    }

    @Override
    public String displayName() {
        return "EastMoney";
    }

    @Override
    public boolean supportsKline(String market) {
        return false;
    }

    /**
     * 迁移自 {@code fetchEastmoneyRealtime(String)}：{@code push2.eastmoney.com/api/qt/stock/get}
     * 的 {@code secid} 前缀规则为 sh → {@code 1.}，其余 → {@code 0.}，代码取 symbol 去掉两位市场前缀。
     * f43/f60/f46/f44/f45/f169 需除以 1000，f170 需除以 100。原实现在空数据时抛异常，这里改为返回 error Map。
     */
    @Override
    public Map<String, Object> quote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Map.of("error", "EastMoney A股标的为空");
        }
        try {
            String normalized = symbol.toLowerCase(Locale.ROOT);
            String secid = normalized.startsWith("sh") ? "1." : "0.";
            String code = normalized.length() > 2 ? normalized.substring(2) : normalized;
            String body = webClient.get()
                    .uri(uriBuilder -> uriBuilder.scheme("https").host("push2.eastmoney.com")
                            .path("/api/qt/stock/get")
                            .queryParam("secid", secid + code)
                            .queryParam("fields", QUOTE_FIELDS)
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            if (body == null || body.isBlank()) {
                return Map.of("error", "EastMoney A股报价为空");
            }
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isObject() || data.path("f43").isMissingNode()) {
                return Map.of("error", "EastMoney A股报价为空");
            }
            double price = data.path("f43").asDouble() / 1000.0;
            double previous = data.path("f60").asDouble() / 1000.0;
            if (price <= 0) {
                return Map.of("error", "EastMoney A股价格为空");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", symbol);
            out.put("name", data.path("f58").asText(symbol));
            out.put("price", price);
            out.put("prev_close", previous);
            out.put("open", data.path("f46").asDouble() / 1000.0);
            out.put("high", data.path("f44").asDouble() / 1000.0);
            out.put("low", data.path("f45").asDouble() / 1000.0);
            out.put("change", data.path("f169").asDouble() / 1000.0);
            out.put("change_pct", data.path("f170").asDouble() / 100.0);
            return out;
        } catch (Exception e) {
            log.warn("EastMoney A股报价失败: symbol={}, message={}", symbol, e.getMessage());
            return Map.of("error", "EastMoney A股报价失败: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        // K 线暂不由本 Provider 承接（见 supportsKline），保持接口完整。
        return List.of();
    }
}