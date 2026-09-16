package com.jarvis.research.market.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.config.JarvisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯行情 Provider。
 *
 * 承接黄金ETF与伦敦金的实时行情，以及黄金ETF的日K线。
 * Provider 只负责数据获取和标准化，不处理缓存、熔断、持久化。
 *
 * 伦敦金日K不在这里——实时行情走腾讯，而日K来自新浪，
 * 因此 {@link #supportsKline(String)} 对 london_gold 返回 false。
 */
@Slf4j
@Component
public class TencentMarketDataProvider implements MarketDataProvider {

    /** 主源优先级。 */
    private static final int PRIORITY = 10;

    private static final Pattern QUOTED_PAYLOAD = Pattern.compile("\"(.+?)\"");

    /** 日K线一次拉取的条数，与原 MarketDataService 行为保持一致。 */
    private static final int KLINE_FETCH_LIMIT = 500;

    /** 黄金ETF标的；单参调用时用它把 ETF 口径与 A 股口径分开。 */
    static final String GOLD_ETF_SYMBOL = "sh518850";

    private final WebClient webClient;
    private final JarvisProperties properties;
    private final ObjectMapper objectMapper;

    public TencentMarketDataProvider(JarvisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = ExternalWebClients.create(java.time.Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "Tencent";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean supports(String market) {
        return "gold_etf".equalsIgnoreCase(market)
                || "london_gold".equalsIgnoreCase(market)
                || "a_share".equalsIgnoreCase(market);
    }

    /**
     * 保持重构前运维已依赖的熔断键名不变。
     */
    @Override
    public String sourceKey(String market) {
        if ("london_gold".equalsIgnoreCase(market)) {
            return "core.tencent.london";
        }
        if ("gold_etf".equalsIgnoreCase(market)) {
            return "core.tencent.etf";
        }
        if ("a_share".equalsIgnoreCase(market)) {
            return "extended.tencent.stock";
        }
        return MarketDataProvider.super.sourceKey(market);
    }

    @Override
    public String displayName() {
        return "Tencent";
    }

    /** 伦敦金日K来自新浪，见 {@link SinaMarketDataProvider}。A股K线尚未迁移，见 supportsKline。 */
    @Override
    public boolean supportsKline(String market) {
        return "gold_etf".equalsIgnoreCase(market);
    }

    /**
     * 单参形式：按标的推断市场后走市场感知版本。
     *
     * <p>保留它是因为 {@link MarketDataProvider#quote(String)} 属冻结契约，
     * 且不关心市场的调用方（以及既有测试）仍按标的调用。</p>
     */
    @Override
    public Map<String, Object> quote(String symbol) {
        return quote(marketOf(symbol), symbol);
    }

    /**
     * 市场感知形式：按**市场**而不是标的决定解析口径。
     *
     * <p>黄金ETF 与 A 股的字段下标完全相同（name[1] price[3] prev[4] open[5]
     * change[31] pct[32] high[33] low[34]），唯一差别是 A 股口径**不产出**
     * {@code source_quote_time}（原 {@code quoteTencent} 就没读字段 30）。
     * 而 {@code sh518850} 与 {@code sh600519} 形态一致，按标的分派必然混淆两个口径。</p>
     */
    @Override
    public Map<String, Object> quote(String market, String symbol) {
        String raw;
        try {
            String url = properties.getGold().getRealtimeUrl().replace("{symbol}", symbol);
            byte[] bytes = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            if (bytes == null) {
                return Map.of("error", "腾讯行情返回空响应");
            }
            raw = new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            log.warn("腾讯实时行情请求失败 symbol={}, message={}", symbol, e.getMessage());
            return Map.of("error", "腾讯行情请求失败: " + e.getMessage());
        }

        Matcher matcher = QUOTED_PAYLOAD.matcher(raw);
        if (!matcher.find()) {
            return Map.of("error", "腾讯行情响应无法解析");
        }

        try {
            if ("london_gold".equalsIgnoreCase(market)) {
                return requirePrice(parseLondonGold(matcher.group(1)));
            }
            if ("a_share".equalsIgnoreCase(market)) {
                return requireASharePrice(parseAShare(symbol, matcher.group(1)));
            }
            if ("gold_etf".equalsIgnoreCase(market)) {
                return requirePrice(parseEtf(symbol, matcher.group(1)));
            }
            return Map.of("error", "腾讯行情不支持该市场: " + market);
        } catch (Exception e) {
            log.warn("腾讯行情解析失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            return Map.of("error", "腾讯行情解析失败: " + e.getMessage());
        }
    }

    /** 单参调用时的市场推断：伦敦金代码 → 黄金ETF 代码 → 其余按 A 股。 */
    static String marketOf(String symbol) {
        if ("london_gold".equalsIgnoreCase(symbol) || "hf_XAU".equalsIgnoreCase(symbol)) {
            return "london_gold";
        }
        if (GOLD_ETF_SYMBOL.equalsIgnoreCase(symbol)) {
            return "gold_etf";
        }
        return "a_share";
    }

    /**
     * A股行情的价格守卫：**只拦 null，不拦 0**。
     *
     * <p>与 {@link #requirePrice} 的差别是有意的。停牌的 A 股在腾讯接口里就是
     * {@code price = 0.00}（配合昨收），原 {@code quoteTencent} 只判 {@code price == null}，
     * 因此停牌股票能正常返回。若这里改用 {@code <= 0} 的严格守卫，
     * 停牌标的会先被腾讯拒绝、再被东方财富拒绝（后者本来就判 {@code <= 0}），
     * 整个 A 股报价直接变成 502 —— 这是行为回归，不是"更严格更安全"。</p>
     */
    Map<String, Object> requireASharePrice(Map<String, Object> quote) {
        if (!(quote.get("price") instanceof Number)) {
            return Map.of("error", "腾讯A股行情价格无效: " + quote.get("price"));
        }
        return quote;
    }

    // ==================== 解析辅助 ====================
    // 以下方法为包级可见（而非 private），以便同包测试在**不联网**的前提下
    // 直接钉住字段下标、null 语义与 change_pct 计算——这些正是重构中最容易悄悄改坏的地方。

    /**
     * 价格缺失或非正时不放行。
     *
     * 上游字段解析不出来时，宁可让业务层看到 {@code error} 去走降级源，也不能把
     * 0（或 null）当成真实价格返回——{@code persistQuotes} 只拦截 {@code price == null}，
     * 一个 0 会被当成有效行情落库。
     */
    Map<String, Object> requirePrice(Map<String, Object> quote) {
        Object raw = quote.get("price");
        if (!(raw instanceof Number price) || price.doubleValue() <= 0) {
            return Map.of("error", "腾讯行情价格无效: " + raw);
        }
        return quote;
    }

    /**
     * 腾讯日K线（前复权）。
     *
     * 无数据或解析失败时返回空列表，由业务层决定是否降级到其他来源。
     */
    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        String body;
        try {
            String param = symbol + ",day,,," + KLINE_FETCH_LIMIT + ",qfq";
            body = webClient.get()
                    .uri(uriBuilder -> uriBuilder.scheme("https").host("web.ifzq.gtimg.cn")
                            .path("/appstock/app/fqkline/get")
                            .queryParam("param", param).build())
                    .retrieve().bodyToMono(String.class).block();
        } catch (Exception e) {
            log.warn("腾讯K线请求失败 symbol={}, message={}", symbol, e.getMessage());
            return List.of();
        }
        if (body == null) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode node = root.path("data").path(symbol);
            if (node.isMissingNode() || node.isNull()) {
                return List.of();
            }
            JsonNode raw = node.get("day");
            if (raw == null || !raw.isArray()) {
                raw = node.get("qfqday");
            }
            if (raw == null || !raw.isArray()) {
                return List.of();
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode item : raw) {
                if (!item.isArray() || item.size() < 5) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", item.get(0).asText());
                row.put("open", Double.parseDouble(item.get(1).asText()));
                row.put("close", Double.parseDouble(item.get(2).asText()));
                row.put("high", Double.parseDouble(item.get(3).asText()));
                row.put("low", Double.parseDouble(item.get(4).asText()));
                row.put("volume", item.size() > 5 ? Double.parseDouble(item.get(5).asText("0")) : 0.0);
                out.add(row);
            }
            return tail(out, limit);
        } catch (Exception e) {
            log.warn("腾讯K线解析失败 symbol={}, message={}", symbol, e.getMessage());
            return List.of();
        }
    }

    /** 取最近 limit 根，保持时间升序（与原落库逻辑一致）。 */
    List<Map<String, Object>> tail(List<Map<String, Object>> rows, int limit) {
        if (limit <= 0 || rows.size() <= limit) {
            return rows;
        }
        return new ArrayList<>(rows.subList(rows.size() - limit, rows.size()));
    }

    Map<String, Object> parseEtf(String symbol, String value) {
        String[] fields = value.split("~");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("name", field(fields, 1, symbol));
        result.put("price", number(fields, 3));
        result.put("prev_close", number(fields, 4));
        result.put("open", number(fields, 5));
        if (fields.length > 30 && fields[30] != null && !fields[30].isBlank()) {
            result.put("source_quote_time", fields[30].trim());
        }
        result.put("change", number(fields, 31));
        result.put("change_pct", number(fields, 32));
        result.put("high", number(fields, 33));
        result.put("low", number(fields, 34));
        return result;
    }

    Map<String, Object> parseLondonGold(String value) {
        String[] fields = value.split(",");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", "hf_XAU");
        result.put("name", field(fields, 13, "伦敦金"));
        result.put("price", number(fields, 0));
        result.put("change", number(fields, 1));
        result.put("open", number(fields, 2));
        result.put("prev_close", number(fields, 3));
        result.put("high", number(fields, 4));
        result.put("low", number(fields, 5));
        if (fields.length > 6 && !fields[6].isBlank()) {
            result.put("source_quote_time", fields[6].trim());
        }
        Double price = (Double) result.get("price");
        Double prev = (Double) result.get("prev_close");
        result.put("change_pct", (price != null && prev != null && prev != 0)
                ? (price - prev) / prev * 100 : 0.0);
        return result;
    }

    String field(String[] values, int index, String fallback) {
        return values.length > index ? values[index] : fallback;
    }

    /**
     * 腾讯 A 股行情。
     *
     * <p>字段下标与 {@link #parseEtf} **完全相同**，唯一差别是不产出
     * {@code source_quote_time}——原 {@code quoteTencent} 就没读字段 30。
     * 少一个键就是少一个键：补上去会改变扩展行情信封的键集。</p>
     *
     * <p>{@code name} 仅在字段 1 非空时产出，与原实现的
     * {@code if (values[1] != null && !values[1].isBlank())} 一致；
     * 为空时由业务层回落到标的登记名，而不是在这里编一个。</p>
     */
    Map<String, Object> parseAShare(String symbol, String value) {
        String[] fields = value.split("~", -1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        String name = field(fields, 1, null);
        if (name != null && !name.isBlank()) {
            result.put("name", name.trim());
        }
        result.put("price", number(fields, 3));
        result.put("prev_close", number(fields, 4));
        result.put("open", number(fields, 5));
        result.put("change", number(fields, 31));
        result.put("change_pct", number(fields, 32));
        result.put("high", number(fields, 33));
        result.put("low", number(fields, 34));
        return result;
    }

    /**
     * 解析失败返回 {@code null}（而非 0），与重构前 {@code parseD} 的语义一致。
     * 返回 0 会把「上游没给这个字段」伪装成「价格真的是 0」。
     */
    Double number(String[] values, int index) {
        if (values.length <= index || values[index] == null || values[index].isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(values[index]);
        } catch (Exception e) {
            return null;
        }
    }
}