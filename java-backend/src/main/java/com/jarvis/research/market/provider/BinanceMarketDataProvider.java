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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Binance（币安）Provider。
 *
 * <p>从 {@code ExtendedMarketDataService#quoteBinance} / {@code #klineBinance} 迁移而来，
 * 作为加密货币市场的**主源**（{@link #priority()} = 10），来源归属键沿用 {@code extended.binance}。</p>
 *
 * <p>标的标准化规则与 {@code ExtendedMarketDataService#parseCrypto} 保持一致：
 * 去掉连字符后按 {@code ^[A-Z0-9]{2,15}(USDT)?$} 校验，缺少计价币时补 {@code USDT}
 * （{@code BTC} → {@code BTCUSDT}）。</p>
 */
@Slf4j
@Component
public class BinanceMarketDataProvider implements MarketDataProvider {

    /** 源端仅支持这些周期；10m 不在其中，它由服务层用 5m 数据聚合。 */
    private static final Set<String> BINANCE_INTERVALS = Set.of("1d", "1h", "30m", "15m", "5m");

    private static final Pattern CRYPTO_PATTERN = Pattern.compile("^[A-Z0-9]{2,15}(USDT)?$");

    /** quote_time 的时区口径与 ExtendedMarketDataService#quoteBase 一致，便于后续切换消费方时无感。 */
    private static final ZoneId CRYPTO_QUOTE_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String TICKER_URL = "https://api.binance.com/api/v3/ticker/24hr?symbol=";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BinanceMarketDataProvider() {
        this.webClient = ExternalWebClients.create(Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "Binance";
    }

    @Override
    public boolean supports(String market) {
        return "crypto".equalsIgnoreCase(market);
    }

    @Override
    public int priority() {
        return 10;
    }

    /** 加密货币只有一个来源键 {@code extended.binance}，与既有熔断/遥测指标保持一致。 */
    @Override
    public String sourceKey(String market) {
        return "extended.binance";
    }

    @Override
    public String displayName() {
        return "Binance";
    }

    /**
     * 迁移自 {@code quoteBinance}：24hr ticker 的文本字段
     * （lastPrice / prevClosePrice / priceChange / priceChangePercent / openPrice / highPrice / lowPrice）
     * 与 {@code closeTime} 毫秒时间戳。原实现价格为空时抛异常，这里改为返回 error Map。
     */
    @Override
    public Map<String, Object> quote(String symbol) {
        String pair;
        try {
            pair = toBinancePair(symbol);
        } catch (IllegalArgumentException e) {
            log.warn("Binance 标的不合法: symbol={}, message={}", symbol, e.getMessage());
            return Map.of("error", e.getMessage());
        }
        try {
            String body = webClient.get()
                    .uri(TICKER_URL + pair)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (body == null || body.isBlank()) {
                return Map.of("error", "加密货币行情为空");
            }
            JsonNode root = objectMapper.readTree(body);
            Double price = textDouble(root, "lastPrice");
            if (price == null) {
                return Map.of("error", "加密货币价格为空");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("market", "crypto");
            out.put("symbol", pair);
            out.put("name", cryptoName(pair));
            out.put("currency", "USDT");
            out.put("source", displayName());
            out.put("price", price);
            out.put("prev_close", textDouble(root, "prevClosePrice"));
            out.put("change", textDouble(root, "priceChange"));
            out.put("change_pct", textDouble(root, "priceChangePercent"));
            out.put("open", textDouble(root, "openPrice"));
            out.put("high", textDouble(root, "highPrice"));
            out.put("low", textDouble(root, "lowPrice"));
            // quote_time 用 Instant.toString()（UTC，带 Z），这是 extended 信封的约定：
            // 扩展行情服务原本就是 Instant.ofEpochMilli(closeTime).toString()。
            //
            // 不要改成带时区的形式。本 Provider 的 quote 此前只被 core 链路引用过，
            // 而 core 链路根本不含 crypto（MarketDataService 只取 gold_etf / london_gold），
            // 所以这个值一直没人真正消费过；一旦加密货币报价接到扩展行情服务上，
            // 它就会直接成为用户看到的报价时间。
            out.put("quote_time", root.path("closeTime").isNumber()
                    ? Instant.ofEpochMilli(root.path("closeTime").asLong()).toString()
                    : LocalDateTime.now().toString());
            return out;
        } catch (Exception e) {
            log.warn("Binance 行情失败: symbol={}, message={}", pair, e.getMessage());
            return Map.of("error", "Binance 行情失败: " + e.getMessage());
        }
    }

    /**
     * 迁移自 {@code klineBinance}：{@code /api/v3/klines} 数组按
     * [开盘时间, 开, 高, 低, 收, 量, ...] 映射，date 用毫秒时间戳的 ISO 字符串。
     * 周期不在 {@link #BINANCE_INTERVALS} 内或标的不合法时返回空列表（K 线契约：无数据即空列表）。
     */
    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        String normalizedInterval = normalizeInterval(interval);
        if (!BINANCE_INTERVALS.contains(normalizedInterval)) {
            log.debug("Binance 不支持该周期: symbol={}, interval={}", symbol, interval);
            return List.of();
        }
        if (limit <= 0) {
            return List.of();
        }
        String pair;
        try {
            pair = toBinancePair(symbol);
        } catch (IllegalArgumentException e) {
            log.warn("Binance K线标的不合法: symbol={}, message={}", symbol, e.getMessage());
            return List.of();
        }
        try {
            String body = webClient.get()
                    .uri("https://api.binance.com/api/v3/klines?symbol=" + pair
                            + "&interval=" + normalizedInterval + "&limit=" + limit)
                    .retrieve().bodyToMono(String.class).block();
            if (body == null || body.isBlank()) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isArray() || item.size() < 6) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", Instant.ofEpochMilli(item.get(0).asLong()).toString());
                row.put("open", item.get(1).asDouble());
                row.put("high", item.get(2).asDouble());
                row.put("low", item.get(3).asDouble());
                row.put("close", item.get(4).asDouble());
                row.put("volume", item.get(5).asDouble());
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            log.warn("Binance K线失败: symbol={}, interval={}, message={}", pair, interval, e.getMessage());
            return List.of();
        }
    }

    /** 与 ExtendedMarketDataService#parseCrypto 相同的标准化：去连字符、校验、补 USDT。 */
    private String toBinancePair(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("加密货币标的不合法: 标的不能为空");
        }
        String pair = symbol.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if (!CRYPTO_PATTERN.matcher(pair).matches()) {
            throw new IllegalArgumentException("加密货币代码格式不正确，例如 BTC、BTCUSDT 或 ETHUSDT");
        }
        return pair.endsWith("USDT") ? pair : pair + "USDT";
    }

    private String cryptoName(String pair) {
        return switch (pair) {
            case "BTCUSDT" -> "Bitcoin";
            case "ETHUSDT" -> "Ethereum";
            case "SOLUSDT" -> "Solana";
            default -> pair + "（自定义标的）";
        };
    }

    /** 与 ExtendedMarketDataService#normalizeInterval 一致：空值/day 归一为 1d。 */
    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank() || "day".equalsIgnoreCase(interval)) {
            return "1d";
        }
        return interval.trim().toLowerCase(Locale.ROOT);
    }

    private Double textDouble(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        return parseDouble(node.path(field).asText(null));
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}