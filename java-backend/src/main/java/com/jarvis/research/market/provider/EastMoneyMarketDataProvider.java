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
import java.util.Set;

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

    /**
     * 包级可见**仅供测试**：注入 WebClient 以便对请求参数与解析做真实的桩测试。
     *
     * <p>有了这个缝，{@code klt}、{@code secid}、{@code lmt} 这些参数才测得到——
     * 它们传错了不会报错，只会静默换一种数据回来。</p>
     */
    EastMoneyMarketDataProvider(WebClient webClient) {
        this.webClient = webClient;
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
     * A股日K与分钟K都由本 Provider 承接，差别只在 {@code klt}。
     *
     * <p>10m 不在其中：它不是来源提供的周期，由服务层用 5m 聚合。
     * 查链时给的也是 5m。</p>
     */
    @Override
    public boolean supportsKline(String market, String interval) {
        if (!"a_share".equalsIgnoreCase(market)) {
            return false;
        }
        return A_SHARE_KLINE_INTERVALS.contains(
                interval == null ? "" : interval.trim().toLowerCase(Locale.ROOT));
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
     * 来源端能给的周期（{@code klt} 值见 {@link #kltOf}）；10m 由服务层用 5m 聚合。
     */
    private static final Set<String> A_SHARE_KLINE_INTERVALS = Set.of("1d", "5m", "15m", "30m", "1h");

    /**
     * 周期 → EastMoney 的 {@code klt}：日线 101，分钟级直接用分钟数（1h = 60）。
     * 返回 null 表示不认识这个周期。
     *
     * <p>包级可见**仅供测试**：这是日K与分钟K之间唯一的差别，值得单独钉住。</p>
     */
    static Integer kltOf(String interval) {
        if (interval == null) {
            return null;
        }
        return switch (interval.trim().toLowerCase(Locale.ROOT)) {
            case "1d" -> 101;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            default -> null;
        };
    }

    /**
     * A股K线（日线与分钟级共用同一接口），迁移自
     * {@code ExtendedMarketDataService#klineEastmoney(Instrument, int)} 与
     * {@code #klineTencentIntraday(...)}。
     *
     * <p>后者的名字是错的——它打的其实是东方财富的接口，与日K**同一个 URL、同一套参数、
     * 同一套解析**，只差 {@code klt}。所以这里合并成一份实现，靠 {@link #kltOf} 分派周期，
     * 而不是留下两段九成相同的代码。</p>
     *
     * <p>{@code fqt=1} 为前复权、{@code lmt} 上限 1000。
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
        Integer klt = kltOf(interval);
        if (klt == null) {
            log.debug("EastMoney 不支持该周期: interval={}", interval);
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
                            .queryParam("klt", klt)
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
            log.warn("EastMoney A股K线失败: symbol={}, interval={}, message={}", symbol, interval, e.getMessage());
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
            // 四条价格必须都能解析，否则**丢掉这一行**而不是丢掉整条K线。
            //
            // 这个守卫是分钟级原有实现的做法（它显式跳过解析不出的行），而日线原有实现是
            // 直接 Double.parseDouble：一行畸形就会抛异常、被上层吞成"无数据"，
            // 于是整条K线变成 502 并触发降级与熔断计数。两份实现合并时必须选一个，
            // 这里选了分钟级那种——单行的畸形不该让整段历史消失。
            //
            // 代价是日线的行为变了：以前"任一行畸形 = 整条不可用"，现在"全行畸形才不可用"。
            // 这是有意的：任一行畸形就丢掉整段是脆弱，不是设计。
            Double open = parseDouble(values[1]);
            Double close = parseDouble(values[2]);
            Double high = parseDouble(values[3]);
            Double low = parseDouble(values[4]);
            if (open == null || close == null || high == null || low == null) {
                continue;
            }
            Double volume = parseDouble(values[5]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", values[0]);
            row.put("open", open);
            row.put("close", close);
            row.put("high", high);
            row.put("low", low);
            row.put("volume", volume == null ? 0.0 : volume);
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
     * 宽松解析：解析不出来返回 null，不抛异常。
     *
     * <p>东方财富在停牌、缺量或返回尚未成形的当根K线时，字段可能是 {@code "-"} 或空串。
     * 直接 {@code Double.parseDouble} 会抛异常，一路冒到最外层 catch 变成"无数据"——
     * 于是一个字段的缺失会把整条K线变成 502 并触发降级与熔断计数。</p>
     */
    private static Double parseDouble(String value) {
        if (value == null || value.isBlank() || "-".equals(value.trim())) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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