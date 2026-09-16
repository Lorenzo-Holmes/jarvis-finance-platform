package com.jarvis.research.market.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
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
        return "a_share".equalsIgnoreCase(market);
    }

    /**
     * A股日K走 {@code push2his.eastmoney.com}，与实时行情 {@code push2.eastmoney.com} 是
     * 两个独立来源，键因此分开。
     */
    @Override
    public String klineSourceKey(String market) {
        if ("a_share".equalsIgnoreCase(market)) {
            return "extended.eastmoney.kline";
        }
        return MarketDataProvider.super.klineSourceKey(market);
    }

    /**
     * 迁移自 {@code ExtendedMarketDataService#klineEastmoney(Instrument, int)}：A股日K备用源。
     *
     * <p>{@code klt=101} 为日线、{@code fqt=1} 为前复权、{@code lmt} 上限 1000。
     * 响应里 {@code data.klines} 是逗号分隔的字符串数组，
     * 顺序为 日期,开,收,高,低,量,f57,f58,f59,f60,f61。</p>
     *
     * <p>原实现在无数据时抛异常，这里改为返回空列表：<b>对 K线而言"空列表"就是失败</b>，
     * 由业务层决定是否降级——这比让它抛异常更贴合 Provider"不处理业务决策"的定位。
     * 服务层在降级链里把空列表当失败处理，与切换前抛异常的效果一致。</p>
     */
    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        try {
            String normalized = symbol.toLowerCase(Locale.ROOT);
            String secid = (normalized.startsWith("sh") ? "1." : "0.")
                    + (normalized.length() > 2 ? normalized.substring(2) : normalized);
            String body = webClient.get()
                    .uri(uriBuilder -> uriBuilder.scheme("https").host("push2his.eastmoney.com")
                            .path("/api/qt/stock/kline/get")
                            .queryParam("secid", secid)
                            .queryParam("klt", 101)
                            .queryParam("fqt", 1)
                            .queryParam("beg", 0)
                            .queryParam("end", 20500000)
                            .queryParam("lmt", Math.min(1000, limit))
                            .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                            .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                            .build())
                    .retrieve().bodyToMono(String.class).block();
            if (body == null || body.isBlank()) {
                return List.of();
            }
            JsonNode raw = objectMapper.readTree(body).path("data").path("klines");
            if (!raw.isArray() || raw.isEmpty()) {
                return List.of();
            }
            return parseKlineRows(raw, limit);
        } catch (Exception e) {
            log.warn("EastMoney A股日K失败: symbol={}, message={}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** 包级可见以便同包测试在**不联网**的前提下钉住字段下标与 limit 截断。 */
    List<Map<String, Object>> parseKlineRows(JsonNode raw, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : raw) {
            String[] values = item.asText().split(",", -1);
            if (values.length < 6) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", values[0]);
            row.put("open", Double.parseDouble(values[1]));
            row.put("close", Double.parseDouble(values[2]));
            row.put("high", Double.parseDouble(values[3]));
            row.put("low", Double.parseDouble(values[4]));
            row.put("volume", Double.parseDouble(values[5]));
            rows.add(row);
        }
        return tail(rows, limit);
    }

    /** 与腾讯 Provider 一致：只保留最后 limit 条。 */
    private static List<Map<String, Object>> tail(List<Map<String, Object>> rows, int limit) {
        int from = Math.max(0, rows.size() - limit);
        return new ArrayList<>(rows.subList(from, rows.size()));
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
}