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
     * 声明真正能取数的市场：黄金（core）、美股与加密货币（extended）。
     *
     * <p>美股与加密货币的报价这次已经实现，所以可以声明。带标的闸门的旧版本只声明
     * {@code london_gold}，是因为当时 {@link #quote} 对权益类标的一律返回 error——
     * 声明支持却必然失败会让注册表把它选进链里、并让熔断器为一个永远不该发生的失败记账。</p>
     *
     * <p>{@code sourceKey} 与市场是否支持是两件事，见 {@link #sourceKey(String)}。</p>
     */
    @Override
    public boolean supports(String market) {
        return "london_gold".equalsIgnoreCase(market)
                || "us_stock".equalsIgnoreCase(market)
                || "crypto".equalsIgnoreCase(market);
    }

    @Override
    public int priority() {
        return 20;
    }

    /**
     * 熔断/遥测归属键。
     *
     * <p>{@code crypto} 显式返回 {@code extended.yahoo.crypto}：接口默认推导会得到
     * {@code core.yahoo.crypto}，而加密货币备用源的既有运维键是 {@code extended.yahoo.*} 一族。
     * 这个差别不会报错，只会让现有的告警与看板突然查不到数——所以必须显式写死。</p>
     */
    @Override
    public String sourceKey(String market) {
        String normalized = market == null ? "" : market.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "london_gold" -> "core.yahoo.gold-futures";
            case "us_stock" -> "extended.yahoo.stock";
            case "crypto" -> "extended.yahoo.crypto";
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
     * 单参形式：按标的推断市场后走市场感知版本。
     *
     * <p>{@link MarketDataProvider#quote(String)} 属冻结契约，保留它以免打断既有调用方。</p>
     */
    @Override
    public Map<String, Object> quote(String symbol) {
        return quote(marketOf(symbol), symbol);
    }

    /**
     * 市场感知的报价入口。
     *
     * <p>两条口径差别很大，不能合并：</p>
     * <ul>
     *   <li><b>黄金（core）</b>：固定取 {@code GC=F}，产出 {@code source_quote_time}
     *       （带时区的 LocalDateTime）——core 信封的约定，由 {@code MarketDataService} 消费</li>
     *   <li><b>美股 / 加密货币（extended）</b>：取入参标的，产出 {@code quote_time}
     *       （{@code Instant.toString()}）——extended 信封的约定。
     *       {@code prev_close} 允许为 null，此时 change 与 change_pct 都为 0</li>
     * </ul>
     */
    @Override
    public Map<String, Object> quote(String market, String symbol) {
        if ("us_stock".equalsIgnoreCase(market) || "crypto".equalsIgnoreCase(market)) {
            return chartMetaQuote(market, symbol);
        }
        return quoteGold();
    }

    /** 单参调用时的市场推断：黄金类 → 加密货币 → 其余按美股。 */
    static String marketOf(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "london_gold";
        }
        String normalized = symbol.trim().toLowerCase(Locale.ROOT);
        if ("gc=f".equals(normalized) || "hf_xau".equals(normalized)
                || "xau".equals(normalized) || "london_gold".equals(normalized)) {
            return "london_gold";
        }
        if (normalized.endsWith("usdt")) {
            return "crypto";
        }
        return "us_stock";
    }

    /**
     * 迁移自 {@code ExtendedMarketDataService#quoteYahoo(Instrument, String)}：
     * 解析 {@code chart.result[0].meta} 的 {@code regularMarketPrice} /
     * {@code previousClose}（缺失回退 {@code chartPreviousClose}）。
     *
     * <p>此前这段逻辑位于扩展行情服务内联代码里，因为 Provider 当时只做黄金。
     * 美股与加密货币备用源共用它——两者只是标的映射不同，解析完全一致。</p>
     */
    private Map<String, Object> chartMetaQuote(String market, String symbol) {
        String providerSymbol;
        try {
            providerSymbol = "crypto".equalsIgnoreCase(market)
                    ? cryptoSymbol(symbol)
                    : stockSymbol(symbol);
        } catch (IllegalArgumentException e) {
            log.warn("Yahoo 标的不合法: market={}, symbol={}, message={}", market, symbol, e.getMessage());
            return Map.of("error", e.getMessage());
        }
        try {
            String body = webClient.get()
                    .uri(chartUrl(providerSymbol, "1d", "1d"))
                    .retrieve().bodyToMono(String.class).block();
            if (body == null || body.isBlank()) {
                return Map.of("error", "Yahoo 行情为空");
            }
            JsonNode result = objectMapper.readTree(body).path("chart").path("result").get(0);
            if (result == null || result.isMissingNode() || result.isNull()) {
                return Map.of("error", "Yahoo 行情为空");
            }
            JsonNode meta = result.path("meta");
            Double price = number(meta, "regularMarketPrice");
            if (price == null) {
                return Map.of("error", "Yahoo 价格为空");
            }
            Double previous = number(meta, "previousClose");
            if (previous == null) {
                previous = number(meta, "chartPreviousClose");
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", symbol);
            String displayName = meta.path("longName").asText(meta.path("shortName").asText(""));
            if (!displayName.isBlank()) {
                // 名称空白时**不产出该键**，由业务层回落到标的登记名，而不是编一个。
                out.put("name", displayName);
            }
            out.put("price", price);
            out.put("prev_close", previous);
            out.put("change", price - (previous == null ? price : previous));
            out.put("change_pct", previous == null || previous == 0
                    ? 0.0 : (price - previous) / previous * 100.0);
            out.put("quote_time", meta.path("regularMarketTime").isNumber()
                    ? Instant.ofEpochSecond(meta.path("regularMarketTime").asLong()).toString()
                    : LocalDateTime.now().toString());
            return out;
        } catch (Exception e) {
            log.warn("Yahoo 行情失败: market={}, symbol={}, message={}", market, symbol, e.getMessage());
            return Map.of("error", "Yahoo 行情失败: " + e.getMessage());
        }
    }

    /** {@code BRK.B} → {@code BRK-B}，与扩展行情服务既有实现一致。 */
    static String stockSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Yahoo 标的不合法: 空标的");
        }
        return symbol.replace('.', '-');
    }

    /** {@code BTCUSDT} → {@code BTC-USD}，与扩展行情服务既有实现一致。 */
    static String cryptoSymbol(String symbol) {
        if (symbol != null && symbol.endsWith("USDT") && symbol.length() > 4) {
            return symbol.substring(0, symbol.length() - 4) + "-USD";
        }
        throw new IllegalArgumentException("不支持的加密货币标的: " + symbol);
    }

    private Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static String chartUrl(String symbol, String range, String interval) {
        return "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?range=" + range + "&interval=" + interval;
    }

    /**
     * 迁移自 {@code fetchYahooGoldRealtime()}：解析 {@code chart.result[0].meta}，
     * 用 {@code regularMarketPrice} 作为现价、{@code previousClose}（缺失时回退
     * {@code chartPreviousClose}）作为昨收。原实现价格为空时抛异常，这里改为返回 error Map。
     */
    private Map<String, Object> quoteGold() {
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
}