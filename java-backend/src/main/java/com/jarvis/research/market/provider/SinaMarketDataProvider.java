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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sina（新浪财经）Provider。
 *
 * <p>从 {@code MarketDataService#fetchLondonKline()} 迁移而来：只承接伦敦金的日 K 线
 * （新浪 GlobalFuturesService JSONP 接口），因此 {@link #supportsQuote(String)} 返回 false，
 * 业务层不会把它排进实时行情降级链，也不会为它产生无意义的熔断失败。</p>
 */
@Slf4j
@Component
public class SinaMarketDataProvider implements MarketDataProvider {

    /** 新浪全球期货接口固定的标的代码，原实现同样固定为 XAU。 */
    private static final String LONDON_GOLD_SYMBOL = "XAU";

    /** JSONP 包装 {@code ([...])} 的提取表达式，需 DOTALL 以跨行匹配。 */
    private static final Pattern JSONP_PATTERN = Pattern.compile("\\(\\[(.*)\\]\\)", Pattern.DOTALL);

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SinaMarketDataProvider() {
        this.webClient = ExternalWebClients.create(Duration.ofSeconds(10));
    }

    @Override
    public String name() {
        return "Sina";
    }

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
        String normalized = market == null ? "" : market.trim().toLowerCase(java.util.Locale.ROOT);
        if ("london_gold".equals(normalized)) {
            return "core.sina.gold-kline";
        }
        return MarketDataProvider.super.sourceKey(market);
    }

    @Override
    public String displayName() {
        return "Sina Finance";
    }

    /** 新浪在本 Provider 中只提供 K 线，不提供实时行情。 */
    @Override
    public boolean supportsQuote(String market) {
        return false;
    }

    @Override
    public Map<String, Object> quote(String symbol) {
        return Map.of("error", "Sina 不提供实时行情");
    }

    /**
     * 迁移自 {@code fetchLondonKline()}：请求
     * {@code stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=XAU}，
     * 带 {@code Referer: https://finance.sina.com.cn}，剥掉 JSONP 包装后映射
     * date/open/close/high/low/volume。失败或无数据时返回空列表。
     *
     * <p>{@code limit > 0} 且行数超出时取**最后** limit 行（保留时间升序），与源数据的时间顺序一致。</p>
     */
    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        try {
            String text = webClient.get().uri(uriBuilder -> uriBuilder
                            .scheme("https").host("stock2.finance.sina.com.cn")
                            .path("/futures/api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesDailyKLine")
                            .queryParam("symbol", LONDON_GOLD_SYMBOL).build())
                    .header("Referer", "https://finance.sina.com.cn")
                    .retrieve().bodyToMono(String.class).block();
            if (text == null) {
                return List.of();
            }
            Matcher matcher = JSONP_PATTERN.matcher(text);
            if (!matcher.find()) {
                return List.of();
            }
            JsonNode raw = objectMapper.readTree("[" + matcher.group(1) + "]");
            if (!raw.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode k : raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", k.path("date").asText());
                item.put("open", Double.parseDouble(k.path("open").asText()));
                item.put("close", Double.parseDouble(k.path("close").asText()));
                item.put("high", Double.parseDouble(k.path("high").asText()));
                item.put("low", Double.parseDouble(k.path("low").asText()));
                item.put("volume", k.hasNonNull("volume")
                        ? Double.parseDouble(k.path("volume").asText("0")) : 0.0);
                out.add(item);
            }
            if (limit > 0 && out.size() > limit) {
                return new ArrayList<>(out.subList(out.size() - limit, out.size()));
            }
            return out;
        } catch (Exception e) {
            log.warn("新浪K线解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}