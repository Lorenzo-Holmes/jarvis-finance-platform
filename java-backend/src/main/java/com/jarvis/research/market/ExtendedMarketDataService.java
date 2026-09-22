package com.jarvis.research.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.market.dto.MarketStatusDTO;
import com.jarvis.research.market.provider.MarketDataProvider;
import com.jarvis.research.market.provider.ProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 股、美股和加密货币的公开行情适配器。
 *
 * <p>默认提供常用标的，并允许用户输入经过市场专属格式校验的自定义 symbol，
 * 避免把任意 URL 或任意外部 symbol 直接透传给行情源。
 * A 股使用腾讯/EastMoney， 美股使用 Yahoo Finance chart，加密货币使用 Binance/Yahoo。
 * 各源由统一熔断器控制，故障时切换到备用源并显式标注 source。</p>
 */
@Slf4j
@Service
public class ExtendedMarketDataService {

    private static final Charset GBK = Charset.forName("GBK");
    private static final long QUOTE_CACHE_NANOS = TimeUnit.MILLISECONDS.toNanos(800);
    private static final long OVERVIEW_CACHE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_QUOTE_CACHE_ENTRIES = 2_000;
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter INTRADAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern HTML_ROW_PATTERN = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_CELL_PATTERN = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String EASTMONEY_AU9999_URL = "https://push2.eastmoney.com/api/qt/stock/get?secid=118.AU9999&fields=f43,f44,f45,f46,f47,f48,f58,f60,f86,f152";
    private static final String SINA_AU9999_URL = "https://hq.sinajs.cn/list=gds_AU9999";
    private static final String SGE_DAILY_URL = "https://www.sge.com.cn/sjzx/quotation_daily_new";
    private static final Pattern SINA_AU9999_PATTERN = Pattern.compile("hq_str_gds_AU9999=\\\"([^\\\"]*)\\\"");

    private record OverviewInstrument(String key, String name, String market, String symbol,
                                      String currency, String region) {}

    private static final List<OverviewInstrument> OVERVIEW_INSTRUMENTS = List.of(
            new OverviewInstrument("sse", "上证指数", "a_share", "sh000001", "CNY", "CN"),
            new OverviewInstrument("chinext", "创业板指", "a_share", "sz399006", "CNY", "CN"),
            new OverviewInstrument("star50", "科创50", "a_share", "sh000688", "CNY", "CN"),
            new OverviewInstrument("szse", "深证成指", "a_share", "sz399001", "CNY", "CN"),
            new OverviewInstrument("bse50", "北证50", "a_share", "bj899050", "CNY", "CN"),
            new OverviewInstrument("sse50", "上证50", "a_share", "sh000016", "CNY", "CN"),
            new OverviewInstrument("dow", "道琼斯", "global_index", "^DJI", "USD", "US"),
            new OverviewInstrument("nasdaq", "纳斯达克", "global_index", "^IXIC", "USD", "US"),
            new OverviewInstrument("sp500", "标普500", "global_index", "^GSPC", "USD", "US"),
            new OverviewInstrument("nasdaq100", "纳斯达克100", "global_index", "^NDX", "USD", "US"),
            new OverviewInstrument("au9999", "黄金9999", "sge_gold", "Au99.99", "CNY/g", "CN"),
            new OverviewInstrument("hsi", "恒生指数", "global_index", "^HSI", "HKD", "HK"),
            new OverviewInstrument("hscei", "恒生国企指数", "global_index", "^HSCE", "HKD", "HK"),
            new OverviewInstrument("hstech", "恒生科技指数", "global_index", "HSTECH.HK", "HKD", "HK")
    );
    /**
     * 对外承诺支持的K线周期，按市场区分（*API 契约*，不是来源能力）。
     *
     * <p>客户端传了个不存在的周期，那是客户端的问题（400），不该掉进降级循环
     * 最后变成"上游网关错误"（502）。</p>
     *
     * <p><b>加密货币有意不在此表中</b>：它一直没有这个校验，同样的错误会得到 502。
     * 这个不对称是重构前就有的，本次原样保留——要统一得单独做，
     * 顺手改掉会让一个既有的 API 行为在重构里悄悄变样。</p>
     */
    private static final Map<String, Set<String>> KLINE_INTERVALS_BY_MARKET = Map.of(
            "a_share", Set.of("1d", "5m", "10m", "15m", "30m", "1h"),
            "us_stock", Set.of("1d", "5m", "10m", "15m", "30m", "1h"),
            "global_index", Set.of("1d"));

    /** 400 文案里的市场名，与切换前逐字一致。 */
    private static String klineMarketLabel(String market) {
        return switch (market) {
            case "a_share" -> "A股";
            case "us_stock" -> "美股";
            default -> market;
        };
    }
    private static final Pattern A_SHARE_PATTERN = Pattern.compile(
            "^(?:(SH|SZ|BJ))?(\\d{6})(?:(SH|SZ|BJ))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern US_STOCK_PATTERN = Pattern.compile(
            "^[A-Z][A-Z0-9.-]{0,9}$");
    private static final Pattern CRYPTO_PATTERN = Pattern.compile(
            "^[A-Z0-9]{2,15}(USDT)?$");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MarketSourceCircuitBreaker circuitBreaker;
    private final MarketDataCacheRepository cacheRepository;
    /**
     * 行情源降级链。A股报价已改由它驱动；其余市场仍在迁移中。
     *
     * <p>可为 null（不加载 Spring 容器的旧测试走这条路），此时 A股报价会明确报"无可用行情源"，
     * 而不是回落到某个内联实现——内联实现已经删掉了，留着半份才是真的危险。</p>
     */
    private final ProviderRegistry providerRegistry;
    /** 多用户秒级轮询时对同一标的做极短缓存，减少重复打第三方报价源。 */
    private final Map<String, CachedQuote> quoteCache = new ConcurrentHashMap<>();
    /** 首页 14 项聚合比单标的更重，单独做 10 秒整页缓存，防止刷新风暴放大第三方请求。 */
    private volatile List<Map<String, Object>> overviewCache = List.of();
    private volatile long overviewCacheExpiresAtNanos = 0L;
    private final AtomicLong quoteCacheWrites = new AtomicLong();

    private record CachedQuote(Map<String, Object> value, long expiresAtNanos) {}

    private final List<Instrument> instruments = List.of(
            new Instrument("a_share", "sh600519", "贵州茅台", "CNY", "Tencent"),
            new Instrument("a_share", "sz000001", "平安银行", "CNY", "Tencent"),
            new Instrument("a_share", "sz300750", "宁德时代", "CNY", "Tencent"),
            new Instrument("us_stock", "AAPL", "Apple", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "MSFT", "Microsoft", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "NVDA", "NVIDIA", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "TSLA", "Tesla", "USD", "Yahoo Finance"),
            new Instrument("crypto", "BTCUSDT", "Bitcoin", "USDT", "Binance"),
            new Instrument("crypto", "ETHUSDT", "Ethereum", "USDT", "Binance"),
            new Instrument("crypto", "SOLUSDT", "Solana", "USDT", "Binance")
    );

    public ExtendedMarketDataService(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null, ExternalWebClients.create(java.time.Duration.ofSeconds(12)));
    }

    @Autowired
    public ExtendedMarketDataService(ObjectMapper objectMapper, MarketSourceCircuitBreaker circuitBreaker,
                                     MarketDataCacheRepository cacheRepository,
                                     ProviderRegistry providerRegistry) {
        this(objectMapper, circuitBreaker, cacheRepository, providerRegistry,
                ExternalWebClients.create(java.time.Duration.ofSeconds(12)));
    }

    ExtendedMarketDataService(ObjectMapper objectMapper, MarketSourceCircuitBreaker circuitBreaker,
                              MarketDataCacheRepository cacheRepository,
                              ProviderRegistry providerRegistry,
                              WebClient webClient) {
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.cacheRepository = cacheRepository;
        this.providerRegistry = providerRegistry;
        this.webClient = webClient;
    }

    /** 兼容不加载 Spring 容器的旧单元测试。 */
    ExtendedMarketDataService(ObjectMapper objectMapper, MarketSourceCircuitBreaker circuitBreaker,
                              WebClient webClient) {
        this(objectMapper, circuitBreaker, null, null, webClient);
    }

    /** 测试用：注入持久化缓存与可伪造的上游客户端，降级链为空。 */
    ExtendedMarketDataService(ObjectMapper objectMapper, MarketSourceCircuitBreaker circuitBreaker,
                              MarketDataCacheRepository cacheRepository, WebClient webClient) {
        this(objectMapper, circuitBreaker, cacheRepository, null, webClient);
    }

    /** 测试用：注入指定的降级链，不启动 Spring 容器。 */
    ExtendedMarketDataService(ObjectMapper objectMapper, MarketSourceCircuitBreaker circuitBreaker,
                              ProviderRegistry providerRegistry, WebClient webClient) {
        this(objectMapper, circuitBreaker, null, providerRegistry, webClient);
    }

    public List<Map<String, Object>> listInstruments() {
        return instruments.stream().map(this::instrumentView).toList();
    }

    /**
     * 行情首页聚合：A股主要指数 + 美港股指数 + 上海黄金交易所 Au99.99。
     * 单项失败只标记该项不可用，不让一个上游故障拖垮整个首屏。
     */
    public synchronized List<Map<String, Object>> marketOverview() {
        long now = System.nanoTime();
        if (!overviewCache.isEmpty() && now < overviewCacheExpiresAtNanos) {
            return copyOverview(overviewCache);
        }
        Map<String, Object> sgeGold = null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (OverviewInstrument item : OVERVIEW_INSTRUMENTS) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("key", item.key());
            card.put("name", item.name());
            card.put("market", item.market());
            card.put("symbol", item.symbol());
            card.put("currency", item.currency());
            card.put("region", item.region());
            try {
                Map<String, Object> quote;
                if ("sge_gold".equals(item.market())) {
                    if (sgeGold == null) sgeGold = au9999Quote();
                    quote = sgeGold;
                } else {
                    quote = quote(item.market(), item.symbol());
                }
                card.putAll(quote);
                // Provider 可能返回自己的名称，但首页文案必须稳定使用产品定义名。
                card.put("name", item.name());
                card.put("available", true);
            } catch (Exception e) {
                log.warn("行情首页单项失败 key={}, market={}, symbol={}, message={}",
                        item.key(), item.market(), item.symbol(), e.getMessage());
                card.put("available", false);
                card.put("error", e.getMessage() == null ? "行情暂不可用" : e.getMessage());
            }
            result.add(card);
        }
        overviewCache = copyOverview(result);
        overviewCacheExpiresAtNanos = now + OVERVIEW_CACHE_NANOS;
        return copyOverview(result);
    }

    private List<Map<String, Object>> copyOverview(List<Map<String, Object>> source) {
        return source.stream()
                .map(item -> (Map<String, Object>) new LinkedHashMap<String, Object>(item))
                .toList();
    }

    /**
     * 黄金9999（Au99.99/AU9999）按同一标的做三级降级：东方财富 → 新浪 → 上金所。
     * 不用伦敦金/XAU 冒充国内 Au99.99；任何备用源都必须仍然是黄金9999本身。
     */
    private Map<String, Object> au9999Quote() {
        try {
            return eastMoneyAu9999Quote();
        } catch (Exception e) {
            log.info("黄金9999东方财富源不可用，尝试新浪: {}", e.getMessage());
        }
        try {
            return sinaAu9999Quote();
        } catch (Exception e) {
            log.info("黄金9999新浪源不可用，尝试上金所: {}", e.getMessage());
        }
        return sgeAu9999Quote();
    }

    /** Au99.99 实时报价专用入口；不为该标的伪造历史K线。 */
    public Map<String, Object> sgeGoldQuote() {
        Map<String, Object> result = new LinkedHashMap<>(au9999Quote());
        result.put("market", "sge_gold");
        result.put("symbol", "Au99.99");
        result.put("name", "黄金9999");
        result.put("available", result.get("price") instanceof Number price && price.doubleValue() > 0);
        return result;
    }

    private Map<String, Object> eastMoneyAu9999Quote() throws Exception {
        String json = webClient.get()
                .uri(EASTMONEY_AU9999_URL)
                .headers(headers -> headers.set("User-Agent", "Mozilla/5.0 (compatible; JARVIS-Market/1.0)"))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (json == null || json.isBlank()) throw upstream("东方财富黄金9999行情为空");

        JsonNode data = objectMapper.readTree(json).path("data");
        if (!data.isObject() || !data.hasNonNull("f43")) throw upstream("东方财富黄金9999返回缺少报价");
        int scaleDigits = data.path("f152").asInt(2);
        double scale = Math.pow(10.0, Math.max(0, Math.min(scaleDigits, 6)));
        double price = data.path("f43").asDouble() / scale;
        double prevClose = data.path("f60").asDouble() / scale;
        if (price <= 0 || prevClose <= 0) throw upstream("东方财富黄金9999报价无效");
        double change = price - prevClose;

        long epochSeconds = data.path("f86").asLong(0L);
        LocalDateTime quoteTime = epochSeconds > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), SHANGHAI_ZONE)
                : LocalDateTime.now(SHANGHAI_ZONE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", "sge_gold");
        out.put("symbol", "Au99.99");
        out.put("price", price);
        out.put("prev_close", prevClose);
        out.put("change", change);
        out.put("change_pct", change / prevClose * 100.0);
        putScaledAuField(out, "high", data, "f44", scale);
        putScaledAuField(out, "low", data, "f45", scale);
        putScaledAuField(out, "open", data, "f46", scale);
        out.put("quote_time", quoteTime.format(INTRADAY_DATE));
        out.put("source", "东方财富（AU9999）");
        out.put("stale", quoteTime.toLocalDate().isBefore(LocalDate.now(SHANGHAI_ZONE)));
        return out;
    }

    private void putScaledAuField(Map<String, Object> out, String key, JsonNode data, String field, double scale) {
        if (!data.hasNonNull(field)) return;
        double value = data.path(field).asDouble() / scale;
        if (value > 0) out.put(key, value);
    }

    private Map<String, Object> sinaAu9999Quote() {
        byte[] bytes = webClient.get()
                .uri(SINA_AU9999_URL)
                .headers(headers -> {
                    headers.set("User-Agent", "Mozilla/5.0 (compatible; JARVIS-Market/1.0)");
                    headers.set("Referer", "https://finance.sina.com.cn/");
                })
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        if (bytes == null || bytes.length == 0) throw upstream("新浪黄金9999行情为空");
        String body = new String(bytes, GBK);
        Matcher matcher = SINA_AU9999_PATTERN.matcher(body);
        if (!matcher.find()) throw upstream("新浪黄金9999返回格式异常");
        String[] fields = matcher.group(1).split(",", -1);
        if (fields.length < 13) throw upstream("新浪黄金9999字段不足");

        Double price = parseMarketNumber(fields[0]);
        Double high = parseMarketNumber(fields[3]);
        Double low = parseMarketNumber(fields[5]);
        Double prevClose = parseMarketNumber(fields[7]);
        Double open = parseMarketNumber(fields[8]);
        if (price == null || price <= 0 || prevClose == null || prevClose <= 0) {
            throw upstream("新浪黄金9999报价无效");
        }
        double change = price - prevClose;
        String quoteDate = fields[12].trim();
        String quoteTime = fields[6].trim();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", "sge_gold");
        out.put("symbol", "Au99.99");
        out.put("price", price);
        out.put("prev_close", prevClose);
        out.put("change", change);
        out.put("change_pct", change / prevClose * 100.0);
        if (open != null && open > 0) out.put("open", open);
        if (high != null && high > 0) out.put("high", high);
        if (low != null && low > 0) out.put("low", low);
        out.put("quote_time", (quoteDate + " " + quoteTime).trim());
        out.put("source", "新浪财经（AU9999）");
        out.put("stale", !LocalDate.now(SHANGHAI_ZONE).toString().equals(quoteDate));
        return out;
    }

    private Map<String, Object> sgeAu9999Quote() {
        LocalDate end = LocalDate.now(SHANGHAI_ZONE);
        LocalDate start = end.minusDays(10);
        String url = SGE_DAILY_URL + "?start_date=" + start + "&end_date=" + end;
        String html = webClient.get()
                .uri(url)
                .headers(headers -> {
                    headers.set("User-Agent", "Mozilla/5.0 (compatible; JARVIS-Market/1.0)");
                    headers.set("Accept", "text/html,application/xhtml+xml");
                    headers.set("Referer", "https://www.sge.com.cn/");
                })
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (html == null || html.isBlank()) throw upstream("上海黄金交易所日行情为空");

        Matcher rows = HTML_ROW_PATTERN.matcher(html);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = HTML_CELL_PATTERN.matcher(rows.group(1));
            while (cellMatcher.find()) cells.add(cleanHtmlCell(cellMatcher.group(1)));
            if (cells.size() < 8 || !"Au99.99".equalsIgnoreCase(cells.get(1))) continue;

            Double open = parseMarketNumber(cells.get(2));
            Double high = parseMarketNumber(cells.get(3));
            Double low = parseMarketNumber(cells.get(4));
            Double close = parseMarketNumber(cells.get(5));
            Double change = parseMarketNumber(cells.get(6));
            Double changePct = parsePercent(cells.get(7));
            if (close == null) continue;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("market", "sge_gold");
            out.put("symbol", "Au99.99");
            out.put("price", close);
            out.put("prev_close", change == null ? null : close - change);
            out.put("change", change == null ? 0.0 : change);
            out.put("change_pct", changePct == null ? 0.0 : changePct);
            out.put("open", open);
            out.put("high", high);
            out.put("low", low);
            out.put("quote_time", cells.get(0));
            out.put("source", "上海黄金交易所（日行情）");
            out.put("stale", !end.toString().equals(cells.get(0)));
            return out;
        }
        throw upstream("上海黄金交易所近10日无 Au99.99 有效行情");
    }

    private String cleanHtmlCell(String value) {
        return value.replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .trim();
    }

    private Double parseMarketNumber(String value) {
        if (value == null) return null;
        String normalized = value.replace(",", "").trim();
        if (normalized.isBlank() || "-".equals(normalized)) return null;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parsePercent(String value) {
        if (value == null) return null;
        return parseMarketNumber(value.replace("%", ""));
    }

    /**
     * 解析用户输入的市场标的。只返回通过市场专属格式校验的标准化 symbol，
     * 后续报价与 K 线接口仍会复用同一套校验，避免将任意输入拼接到外部 URL。
     */
    public Map<String, Object> resolveInstrument(String market, String query) {
        Instrument instrument = parseInstrument(market, query);
        try {
            Map<String, Object> resolved = switch (instrument.market()) {
                case "a_share" -> registryQuote(instrument, "EastMoney (fallback)");
                case "us_stock" -> registryQuote(instrument, "Yahoo Finance (fallback)");
                case "crypto" -> registryQuote(instrument, "Yahoo Finance (fallback)");
                case "global_index" -> registryQuote(instrument, "Yahoo Finance (fallback)");
                default -> Map.of();
            };
            Object resolvedName = resolved.get("name");
            if (resolvedName instanceof String name && !name.isBlank()) {
                instrument = instrument.withName(name.trim());
            }
        } catch (Exception e) {
            // 解析本身仍可返回标准化代码；行情源暂时不可用时由后续报价接口给出明确错误。
            log.info("自定义标的名称解析暂不可用 market={}, symbol={}, message={}",
                    instrument.market(), instrument.symbol(), e.getMessage());
        }
        return instrumentView(instrument);
    }

    /** 返回交易时段状态；节假日历未接入，因此只按工作日和交易时段判断。 */
    public MarketStatusDTO session(String market) {
        String normalizedMarket = normalizeMarket(market);
        LocalDateTime now = LocalDateTime.now(zoneFor(normalizedMarket));
        boolean weekday = now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY;
        boolean open = switch (normalizedMarket) {
            case "crypto" -> true;
            case "a_share" -> weekday && inAnySession(now.toLocalTime(),
                    LocalTime.of(9, 30), LocalTime.of(11, 30),
                    LocalTime.of(13, 0), LocalTime.of(15, 0));
            case "us_stock" -> weekday && inAnySession(now.toLocalTime(),
                    LocalTime.of(9, 30), LocalTime.of(16, 0));
            default -> false;
        };
        return new MarketStatusDTO(
                normalizedMarket,
                open,
                open ? "open" : "closed",
                open ? "交易中" : MarketStatusDTO.DEFAULT_CLOSED_LABEL,
                zoneFor(normalizedMarket).getId(),
                now.toString(),
                "交易状态按工作日和常规时段估算，未接入交易所节假日历");
    }

    public Map<String, Object> quote(String market, String symbol) {
        Instrument instrument = requireInstrument(market, symbol);
        String cacheKey = quoteCacheKey(instrument.market(), instrument.symbol());
        long now = System.nanoTime();
        CachedQuote cached = quoteCache.get(cacheKey);
        if (cached != null && now < cached.expiresAtNanos()) {
            return new LinkedHashMap<>(cached.value());
        }
        try {
            Map<String, Object> result = switch (instrument.market()) {
                case "a_share" -> registryQuote(instrument, "EastMoney (fallback)");
                case "us_stock" -> registryQuote(instrument, "Yahoo Finance (fallback)");
                case "crypto" -> registryQuote(instrument, "Yahoo Finance (fallback)");
                case "global_index" -> registryQuote(instrument, "Yahoo Finance (fallback)");
                default -> throw invalid("不支持的市场: " + instrument.market());
            };
            cacheQuote(cacheKey, result, now);
            return result;
        } catch (ResponseStatusException e) {
            Map<String, Object> cachedResult = readCachedQuote(cacheKey);
            if (cachedResult != null) return cachedResult;
            throw e;
        } catch (Exception e) {
            log.warn("扩展行情源调用失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            Map<String, Object> cachedResult = readCachedQuote(cacheKey);
            if (cachedResult != null) return cachedResult;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "行情源暂不可用，请稍后重试");
        }
    }

    private void cacheQuote(String cacheKey, Map<String, Object> result, long now) {
        long writes = quoteCacheWrites.incrementAndGet();
        if (quoteCache.size() >= MAX_QUOTE_CACHE_ENTRIES || (writes & 255L) == 0L) {
            quoteCache.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos());
        }
        if (quoteCache.size() >= MAX_QUOTE_CACHE_ENTRIES && !quoteCache.containsKey(cacheKey)) {
            quoteCache.keySet().stream().findFirst().ifPresent(quoteCache::remove);
        }
        quoteCache.put(cacheKey, new CachedQuote(new LinkedHashMap<>(result), now + QUOTE_CACHE_NANOS));
        writeCache(cacheKey, "quote", result, result.get("source"), null,
                String.valueOf(result.getOrDefault("symbol", "")),
                String.valueOf(result.getOrDefault("market", "")));
    }

    int quoteCacheSize() {
        return quoteCache.size();
    }

    public Map<String, Object> kline(String market, String symbol, String interval, int limit) {
        Instrument instrument = requireInstrument(market, symbol);
        if (limit < 1 || limit > 500) {
            throw invalid("limit 必须在 1~500 之间");
        }
        String normalized = normalizeInterval(interval);
        try {
            List<Map<String, Object>> data = switch (instrument.market()) {
                // 三个市场现在同一条路：周期白名单、来源能力、降级都在 registryKline 里。
                case "a_share", "us_stock", "crypto", "global_index" -> registryKline(instrument, normalized, limit);
                default -> throw invalid("不支持的市场: " + instrument.market());
            };
            writeCache(klineCacheKey(instrument, normalized), "kline", data,
                    dataSource(data), normalized, instrument.symbol(), instrument.market());
            return klineResponse(instrument, normalized, data, false);
        } catch (ResponseStatusException e) {
            List<Map<String, Object>> cached = readCachedKline(instrument, normalized);
            if (cached != null && !cached.isEmpty()) return klineResponse(instrument, normalized, cached, true);
            throw e;
        } catch (Exception e) {
            log.warn("扩展K线源调用失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            List<Map<String, Object>> cached = readCachedKline(instrument, normalized);
            if (cached != null && !cached.isEmpty()) return klineResponse(instrument, normalized, cached, true);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "K线源暂不可用，请稍后重试");
        }
    }

    private Map<String, Object> klineResponse(Instrument instrument, String interval,
                                               List<Map<String, Object>> data, boolean stale) {
        Map<String, Object> technicalAnalysis = enrichTechnicalIndicators(data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", instrument.market());
        out.put("symbol", instrument.symbol());
        out.put("interval", interval);
        out.put("count", data.size());
        out.put("data", data);
        out.put("analysis", technicalAnalysis);
        out.put("stale", stale);
        out.put("source", stale ? "local-cache" : dataSource(data));
        if (!data.isEmpty()) {
            out.put("range", Map.of(
                    "start", data.get(0).get("date"),
                    "end", data.get(data.size() - 1).get("date"),
                    "count", data.size()));
        }
        return out;
    }

    private String quoteCacheKey(String market, String symbol) {
        return "quote:" + market + ":" + symbol;
    }

    private String klineCacheKey(Instrument instrument, String interval) {
        return "kline:" + instrument.market() + ":" + instrument.symbol() + ":" + interval;
    }

    private String dataSource(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) return null;
        Object source = data.get(0).get("source");
        return source == null ? null : String.valueOf(source);
    }

    private void writeCache(String cacheKey, String kind, Object payload, Object source,
                            String interval, String symbol, String market) {
        if (cacheRepository == null) return;
        try {
            MarketDataCache cache = cacheRepository.findByCacheKey(cacheKey).orElseGet(MarketDataCache::new);
            cache.setCacheKey(cacheKey);
            cache.setKind(kind);
            cache.setMarket(market == null ? "" : market);
            cache.setSymbol(symbol == null ? "" : symbol);
            cache.setInterval(interval);
            cache.setPayload(objectMapper.writeValueAsString(payload));
            cache.setSource(source == null ? null : String.valueOf(source));
            cache.setUpdatedAt(LocalDateTime.now());
            cacheRepository.save(cache);
        } catch (Exception e) {
            log.warn("扩展行情缓存写入失败 cacheKey={}, message={}", cacheKey, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCachedQuote(String cacheKey) {
        if (cacheRepository == null) return null;
        try {
            MarketDataCache cache = cacheRepository.findByCacheKey(cacheKey).orElse(null);
            if (cache == null || !"quote".equals(cache.getKind())) return null;
            Map<String, Object> result = objectMapper.readValue(cache.getPayload(), Map.class);
            result = new LinkedHashMap<>(result);
            result.put("stale", true);
            result.put("cached_at", cache.getUpdatedAt() == null ? null : cache.getUpdatedAt().toString());
            result.put("source", (cache.getSource() == null ? "unknown" : cache.getSource()) + " (local-cache)");
            return result;
        } catch (Exception e) {
            log.warn("扩展行情缓存读取失败 cacheKey={}, message={}", cacheKey, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCachedKline(Instrument instrument, String interval) {
        if (cacheRepository == null) return null;
        try {
            MarketDataCache cache = cacheRepository.findByCacheKey(klineCacheKey(instrument, interval)).orElse(null);
            if (cache == null || !"kline".equals(cache.getKind())) return null;
            List<?> rows = objectMapper.readValue(cache.getPayload(), List.class);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    result.add(normalized);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("扩展K线缓存读取失败 market={}, symbol={}, interval={}, message={}",
                    instrument.market(), instrument.symbol(), interval, e.getMessage());
            return null;
        }
    }

    /**
     * 按 {@link ProviderRegistry} 的降级链取行情，并包装成扩展行情信封。
     *
     * <p>信封语义与切换前的内联实现逐项对齐：</p>
     * <ul>
     *   <li>基础字段来自 {@link #quoteBase}（market/symbol/name/currency/source/quote_time），
     *       再由 Provider 的字段覆盖。{@code name} 因此保持原语义——Provider 给了就用它的，
     *       没给（字段空白）就回落到标的登记名，而不是编一个</li>
     *   <li>{@code quote_time} 统一覆盖为 {@code LocalDateTime.now().toString()}，
     *       与原 {@code quoteTencent} 最后的覆盖一致（不是 quoteBase 里那个带时区的形式）</li>
     *   <li>{@code source}：主源沿用标的登记名，备用源用调用方给的标签。
     *       标签**必须显式传入**而不是由 provider.displayName() 拼——Yahoo 的 displayName 是
     *       "Yahoo Finance (GC=F 期货)"，拼出来的备用标签会是错的</li>
     * </ul>
     *
     * <p>A股K线此时尚未迁移到 Provider，所以这里只管报价；熔断键沿用 Provider 的
     * {@link MarketDataProvider#sourceKey(String)}（extended.tencent.stock / extended.eastmoney.stock）。</p>
     */
    private Map<String, Object> registryQuote(Instrument instrument, String fallbackLabel) {
        List<MarketDataProvider> chain = providerRegistry == null
                ? List.of()
                : providerRegistry.quoteChain(instrument.market());
        if (chain.isEmpty()) {
            throw upstream(instrument.market() + " 无可用行情源");
        }

        String lastError = "行情源均不可用: " + instrument.market();
        for (int index = 0; index < chain.size(); index++) {
            MarketDataProvider provider = chain.get(index);
            String source = provider.sourceKey(instrument.market());
            if (!allowSource(source)) {
                lastError = "行情源熔断中: " + source;
                continue;
            }
            try {
                Map<String, Object> raw = provider.quote(instrument.market(), instrument.symbol());
                if (raw == null || raw.containsKey("error")) {
                    throw new IllegalStateException(raw == null
                            ? "行情源返回空结果"
                            : String.valueOf(raw.get("error")));
                }
                recordSourceSuccess(source);
                Map<String, Object> out = quoteBase(instrument);
                out.putAll(raw);
                out.put("source", index == 0 ? instrument.source() : fallbackLabel);
                // quote_time 由 Provider 决定：它给了就用它的（那是行情源自己的成交时间），
                // 没给才由这里补成本地当前时间。
                //
                // 判据必须是 raw（Provider 的产出），**不能是 out**——quoteBase 已经先放了一个
                // 带时区的占位值（...+08:00[Asia/Shanghai]），拿 out 判永远不会命中，
                // 于是 A股的 quote_time 会从原本不带时区的 LocalDateTime 悄悄变成带时区的 ZonedDateTime。
                // 这个错误当时真的写出来了，是被 A股契约测试当场拦下的。
                //
                // A股不该产出 Provider 时间（腾讯口径本来就没有），所以由这里补；
                // 而美股与加密货币的 quote_time 是**行情源的市场时间**
                // （Yahoo regularMarketTime / Binance closeTime，Instant 形态），必须原样保留，
                // 否则用户看到的报价时间会从"上游成交时刻"退化成"我们取数的时刻"。
                if (!raw.containsKey("quote_time")) {
                    out.put("quote_time", LocalDateTime.now().toString());
                }
                return out;
            } catch (Exception e) {
                recordSourceFailure(source);
                log.warn("扩展行情源失败，尝试下一个 market={}, provider={}, source={}, message={}",
                        instrument.market(), provider.name(), source, e.getMessage());
                lastError = e.getMessage() == null ? "行情源调用失败" : e.getMessage();
            }
        }
        throw upstream(lastError);
    }

    /**
     * 按注册表取K线，并保持切换前的降级语义。
     *
     * <p>与报价的一个关键差别：**空列表等同于失败**。切换前的 A股内联实现
     * 在无数据时抛异常、从而触发降级；Provider 按契约返回空列表，
     * 所以判定失败的活儿落在这一层——否则"K线为空"会当成功返回，降级永远不会发生。</p>
     *
     * <p>10 分钟周期在这里用 5 分钟数据聚合：它是个**派生**周期，没有任何来源真正提供它。
     * 放进 Provider 会让 Provider 对外声称支持一个上游并不存在的周期。</p>
     *
     * <p>是否参与熔断也由 Provider 决定（默认参与）。美股K线刻意不参与，
     * 理由是它的熔断键与美股报价共用，见
     * {@link MarketDataProvider#klineUsesCircuitBreaker(String)}。</p>
     */
    private List<Map<String, Object>> registryKline(Instrument instrument, String interval, int limit) {
        // 周期白名单是**对外 API 契约**，不是来源能力。
        Set<String> allowed = KLINE_INTERVALS_BY_MARKET.get(instrument.market());
        if (allowed != null && !allowed.contains(interval)) {
            throw invalid(klineMarketLabel(instrument.market()) + "不支持该周期: " + interval);
        }

        // 查链用的是**来源要取的周期**：10 分钟由本层用 5 分钟聚合，
        // 所以问的是"谁能给 5 分钟"，而不是"谁能给 10 分钟"——没有来源能直接给 10 分钟。
        String sourceInterval = "10m".equals(interval) ? "5m" : interval;
        List<MarketDataProvider> chain = providerRegistry == null
                ? List.of()
                : providerRegistry.klineChain(instrument.market(), sourceInterval);
        if (chain.isEmpty()) {
            throw upstream(instrument.market() + " 无可用K线源");
        }

        String lastError = "K线源均不可用: " + instrument.market();
        for (MarketDataProvider provider : chain) {
            boolean guarded = provider.klineUsesCircuitBreaker(instrument.market());
            String source = provider.klineSourceKey(instrument.market());
            if (guarded && !allowSource(source)) {
                lastError = "K线源熔断中: " + source;
                continue;
            }
            try {
                List<Map<String, Object>> rows = fetchKlineFrom(provider, instrument, interval, limit);
                if (rows == null || rows.isEmpty()) {
                    throw new IllegalStateException("K线源返回空数据");
                }
                if (guarded) recordSourceSuccess(source);
                return rows;
            } catch (Exception e) {
                if (guarded) recordSourceFailure(source);
                log.warn("扩展K线源失败，尝试下一个 market={}, provider={}, source={}, message={}",
                        instrument.market(), provider.name(), source, e.getMessage());
                lastError = e.getMessage() == null ? "K线源调用失败" : e.getMessage();
            }
        }
        throw upstream(lastError);
    }

    /**
     * 从单个 Provider 取K线；10 分钟周期先取 5 分钟再聚合。
     *
     * <p>一次取多少原始K线取决于 {@link MarketDataProvider#maxKlineLimit()}：
     * Yahoo 上限 500、Binance 1000。这个差别是真实存在的，用统一上限会让某一侧的
     * 10 分钟视图在 limit 较大时多出一段本没有的历史。</p>
     */
    private List<Map<String, Object>> fetchKlineFrom(MarketDataProvider provider, Instrument instrument,
                                                     String interval, int limit) {
        if ("10m".equals(interval)) {
            int rawLimit = Math.min(provider.maxKlineLimit(), limit * 3);
            return aggregateCandles(
                    provider.kline(instrument.market(), instrument.symbol(), "5m", rawLimit), 10, limit);
        }
        return provider.kline(instrument.market(), instrument.symbol(), interval, limit);
    }

    private boolean allowSource(String source) {
        return circuitBreaker == null || circuitBreaker.allowRequest(source);
    }

    private void recordSourceSuccess(String source) {
        if (circuitBreaker != null) circuitBreaker.recordSuccess(source);
    }

    private void recordSourceFailure(String source) {
        if (circuitBreaker != null) circuitBreaker.recordFailure(source);
    }

    private List<Map<String, Object>> aggregateCandles(List<Map<String, Object>> rows, int minutes, int limit) {
        long bucketSize = minutes * 60L;
        Map<Long, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long epoch = epochSeconds(row.get("date"));
            long bucket = Math.floorDiv(epoch, bucketSize) * bucketSize;
            Map<String, Object> target = buckets.computeIfAbsent(bucket, ignored -> {
                Map<String, Object> initial = new LinkedHashMap<>();
                initial.put("date", row.get("date"));
                initial.put("open", row.get("open"));
                initial.put("close", row.get("close"));
                initial.put("high", row.get("high"));
                initial.put("low", row.get("low"));
                // 成交量从 0 起算，**不能**先塞首根的成交量：computeIfAbsent 之后那段
                // 无条件累加会把当前这根再加一次，于是每个桶的首根被算了两次。
                //
                // 这是本次重构前就存在的错误（原先只影响 10 分钟周期，
                // 因为只有它走聚合），结果是 10 分钟K线的成交量恒偏高"一根5分钟"的量。
                // 高/低没事是因为 max/min 幂等，收盘价是覆盖写，只有成交量会累加。
                // 成交量会流进 enrichTechnicalIndicators 的量能指标，所以不是显示问题。
                initial.put("volume", 0.0);
                return initial;
            });
            target.put("close", row.get("close"));
            target.put("high", Math.max(((Number) target.get("high")).doubleValue(), ((Number) row.get("high")).doubleValue()));
            target.put("low", Math.min(((Number) target.get("low")).doubleValue(), ((Number) row.get("low")).doubleValue()));
            target.put("volume", ((Number) target.get("volume")).doubleValue()
                    + ((Number) row.getOrDefault("volume", 0.0)).doubleValue());
        }
        return tail(new ArrayList<>(buckets.values()), limit);
    }

    private long epochSeconds(Object value) {
        String date = String.valueOf(value);
        try {
            return Instant.parse(date).getEpochSecond();
        } catch (Exception ignored) {
            return LocalDateTime.parse(date, INTRADAY_DATE).atZone(SHANGHAI_ZONE).toEpochSecond();
        }
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank() || "day".equalsIgnoreCase(interval)) return "1d";
        return interval.trim().toLowerCase();
    }

    private Instrument requireInstrument(String market, String symbol) {
        if (market == null || symbol == null) throw invalid("市场和标的不能为空");
        String normalizedMarket = normalizeMarket(market);
        String normalizedSymbol = symbol.trim();
        return instruments.stream()
                .filter(item -> item.market().equals(normalizedMarket)
                        && item.symbol().equalsIgnoreCase(normalizedSymbol))
                .findFirst()
                .orElseGet(() -> parseInstrument(normalizedMarket, normalizedSymbol));
    }

    private Instrument parseInstrument(String market, String query) {
        if (market == null || query == null || query.isBlank()) {
            throw invalid("市场和标的不能为空");
        }
        String normalizedMarket = normalizeMarket(market);
        String raw = query.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedMarket) {
            case "a_share" -> parseAShare(raw);
            case "us_stock" -> parseUsStock(raw);
            case "crypto" -> parseCrypto(raw);
            case "global_index" -> parseGlobalIndex(raw);
            default -> throw invalid("不支持的市场: " + market);
        };
    }

    private String normalizeMarket(String market) {
        String normalized = market.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("a_share", "us_stock", "crypto", "global_index").contains(normalized)) {
            throw invalid("不支持的市场: " + market);
        }
        return normalized;
    }

    private Instrument parseAShare(String raw) {
        String compact = raw.replace(".", "").replace("-", "");
        Matcher matcher = A_SHARE_PATTERN.matcher(compact);
        if (!matcher.matches()) throw invalid("A股代码应为 6 位数字，例如 600519 或 SH600519");
        String exchange = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        String code = matcher.group(2);
        if (exchange == null) {
            exchange = code.startsWith("6") ? "SH" : code.startsWith("4") || code.startsWith("8") ? "BJ" : "SZ";
        }
        String symbol = exchange.toLowerCase(Locale.ROOT) + code;
        String name = switch (symbol) {
            case "sh600519" -> "贵州茅台";
            case "sz000001" -> "平安银行";
            case "sz300750" -> "宁德时代";
            default -> code + "（自定义标的）";
        };
        return new Instrument("a_share", symbol, name, "CNY", "Tencent");
    }

    private Instrument parseUsStock(String raw) {
        String symbol = raw.startsWith("$") ? raw.substring(1) : raw;
        if (!US_STOCK_PATTERN.matcher(symbol).matches()) {
            throw invalid("美股代码格式不正确，例如 AAPL、MSFT 或 BRK.B");
        }
        String name = switch (symbol) {
            case "AAPL" -> "Apple";
            case "MSFT" -> "Microsoft";
            case "NVDA" -> "NVIDIA";
            case "TSLA" -> "Tesla";
            default -> symbol + "（自定义标的）";
        };
        return new Instrument("us_stock", symbol, name, "USD", "Yahoo Finance");
    }

    private Instrument parseGlobalIndex(String raw) {
        return switch (raw) {
            case "^DJI" -> new Instrument("global_index", raw, "道琼斯", "USD", "Yahoo Finance");
            case "^IXIC" -> new Instrument("global_index", raw, "纳斯达克", "USD", "Yahoo Finance");
            case "^GSPC" -> new Instrument("global_index", raw, "标普500", "USD", "Yahoo Finance");
            case "^NDX" -> new Instrument("global_index", raw, "纳斯达克100", "USD", "Yahoo Finance");
            case "^HSI" -> new Instrument("global_index", raw, "恒生指数", "HKD", "Yahoo Finance");
            case "^HSCE" -> new Instrument("global_index", raw, "恒生国企指数", "HKD", "Yahoo Finance");
            case "HSTECH.HK" -> new Instrument("global_index", raw, "恒生科技指数", "HKD", "Yahoo Finance");
            default -> throw invalid("不支持的全球指数代码: " + raw);
        };
    }

    private Instrument parseCrypto(String raw) {
        String symbol = raw.replace("-", "");
        if (!CRYPTO_PATTERN.matcher(symbol).matches()) {
            throw invalid("加密货币代码格式不正确，例如 BTC、BTCUSDT 或 ETHUSDT");
        }
        if (!symbol.endsWith("USDT")) symbol += "USDT";
        String name = switch (symbol) {
            case "BTCUSDT" -> "Bitcoin";
            case "ETHUSDT" -> "Ethereum";
            case "SOLUSDT" -> "Solana";
            default -> symbol + "（自定义标的）";
        };
        return new Instrument("crypto", symbol, name, "USDT", "Binance");
    }

    private Map<String, Object> instrumentView(Instrument item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", item.market());
        out.put("symbol", item.symbol());
        out.put("name", item.name());
        out.put("currency", item.currency());
        out.put("source", item.source());
        return out;
    }

    private Map<String, Object> quoteBase(Instrument item) {
        Map<String, Object> out = instrumentView(item);
        out.put("quote_time", LocalDateTime.now().atZone(ZoneId.of("Asia/Shanghai")).toString());
        return out;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> rows, int limit) {
        int from = Math.max(0, rows.size() - limit);
        return new ArrayList<>(rows.subList(from, rows.size()));
    }

    /**
     * 计算可复核的基础技术指标。这里不使用大模型，也不输出买卖建议；AI 只负责在这些数据之上生成研究性解读。
     */
    Map<String, Object> enrichTechnicalIndicators(List<Map<String, Object>> rows) {
        List<Double> closes = rows.stream().map(row -> numeric(row, "close")).toList();
        List<Double> highs = rows.stream().map(row -> numeric(row, "high")).toList();
        List<Double> lows = rows.stream().map(row -> numeric(row, "low")).toList();
        List<Double> volumes = rows.stream().map(row -> numeric(row, "volume")).toList();
        List<Double> ema12 = ema(closes, 12);
        List<Double> ema26 = ema(closes, 26);
        List<Double> macd = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            macd.add(ema12.get(i) == null || ema26.get(i) == null ? null : ema12.get(i) - ema26.get(i));
        }
        List<Double> signal = ema(macd, 9);
        List<Double> rsi14 = rsi(closes, 14);
        List<Double> sma5 = sma(closes, 5);
        List<Double> sma20 = sma(closes, 20);
        List<Double> bollingerStd20 = rollingStdDev(closes, 20);
        List<Double> bollingerUpper = combine(sma20, bollingerStd20, (middle, deviation) -> middle + deviation * 2);
        List<Double> bollingerLower = combine(sma20, bollingerStd20, (middle, deviation) -> middle - deviation * 2);
        List<Double> atr14 = atr(rows, 14);
        List<Double> stochasticK14 = stochasticK(closes, highs, lows, 14);
        List<Double> stochasticD3 = sma(stochasticK14, 3);
        List<Double> williamsR14 = williamsR(closes, highs, lows, 14);
        List<Double> adx14 = adx(rows, 14);
        List<Double> volumeSma20 = sma(volumes, 20);
        List<Double> obv = obv(closes, volumes);
        List<Double> roc12 = rateOfChange(closes, 12);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            putIfPresent(row, "sma5", at(sma5, i));
            putIfPresent(row, "sma20", at(sma20, i));
            putIfPresent(row, "ema12", at(ema12, i));
            putIfPresent(row, "ema26", at(ema26, i));
            putIfPresent(row, "rsi14", at(rsi14, i));
            putIfPresent(row, "bollinger_middle", at(sma20, i));
            putIfPresent(row, "bollinger_upper", at(bollingerUpper, i));
            putIfPresent(row, "bollinger_lower", at(bollingerLower, i));
            putIfPresent(row, "atr14", at(atr14, i));
            putIfPresent(row, "stoch_k14", at(stochasticK14, i));
            putIfPresent(row, "stoch_d3", at(stochasticD3, i));
            putIfPresent(row, "williams_r14", at(williamsR14, i));
            putIfPresent(row, "adx14", at(adx14, i));
            putIfPresent(row, "volume_sma20", at(volumeSma20, i));
            putIfPresent(row, "obv", at(obv, i));
            putIfPresent(row, "roc12", at(roc12, i));
            Double macdValue = at(macd, i);
            Double signalValue = at(signal, i);
            putIfPresent(row, "macd", macdValue);
            putIfPresent(row, "macd_signal", signalValue);
            putIfPresent(row, "macd_histogram",
                    macdValue == null || signalValue == null ? null : macdValue - signalValue);
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            analysis.put("status", "insufficient_data");
            analysis.put("message", "暂无足够K线数据");
            return analysis;
        }
        int latestIndex = rows.size() - 1;
        Double close = numeric(rows.get(latestIndex), "close");
        Double latestSma20 = at(sma20, latestIndex);
        Double latestEma12 = at(ema12, latestIndex);
        Double latestEma26 = at(ema26, latestIndex);
        Double latestRsi = at(rsi14, latestIndex);
        Double latestMacd = at(macd, latestIndex);
        Double latestSignal = at(signal, latestIndex);
        Double latestBollingerMiddle = at(sma20, latestIndex);
        Double latestBollingerUpper = at(bollingerUpper, latestIndex);
        Double latestBollingerLower = at(bollingerLower, latestIndex);

        String trend = "neutral";
        String trendLabel = "震荡观察";
        if (close != null && latestSma20 != null && latestEma12 != null && latestEma26 != null) {
            if (close > latestSma20 && latestEma12 > latestEma26) {
                trend = "bullish";
                trendLabel = "偏强：价格在20期均线上方，短期均线向上";
            } else if (close < latestSma20 && latestEma12 < latestEma26) {
                trend = "bearish";
                trendLabel = "偏弱：价格在20期均线下方，短期均线向下";
            }
        }
        String momentum = "normal";
        String momentumLabel = "动能中性";
        if (latestRsi != null && latestRsi >= 70) {
            momentum = "overbought";
            momentumLabel = "RSI偏高，注意短线过热";
        } else if (latestRsi != null && latestRsi <= 30) {
            momentum = "oversold";
            momentumLabel = "RSI偏低，注意短线超跌";
        }

        int windowStart = Math.max(0, rows.size() - 20);
        double support = rows.subList(windowStart, rows.size()).stream()
                .mapToDouble(row -> numeric(row, "low") == null ? Double.POSITIVE_INFINITY : numeric(row, "low"))
                .min().orElse(Double.NaN);
        double resistance = rows.subList(windowStart, rows.size()).stream()
                .mapToDouble(row -> numeric(row, "high") == null ? Double.NEGATIVE_INFINITY : numeric(row, "high"))
                .max().orElse(Double.NaN);

        analysis.put("status", "ok");
        analysis.put("trend", trend);
        analysis.put("trend_label", trendLabel);
        analysis.put("momentum", momentum);
        analysis.put("momentum_label", momentumLabel);
        analysis.put("support_20", finiteOrNull(support));
        analysis.put("resistance_20", finiteOrNull(resistance));
        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("close", close);
        indicators.put("sma20", latestSma20);
        indicators.put("ema12", latestEma12);
        indicators.put("ema26", latestEma26);
        indicators.put("rsi14", latestRsi);
        indicators.put("macd", latestMacd);
        indicators.put("macd_signal", latestSignal);
        indicators.put("bollinger_middle", latestBollingerMiddle);
        indicators.put("bollinger_upper", latestBollingerUpper);
        indicators.put("bollinger_lower", latestBollingerLower);
        indicators.put("bollinger_width", latestBollingerMiddle == null || latestBollingerMiddle == 0
                || latestBollingerUpper == null || latestBollingerLower == null
                ? null : (latestBollingerUpper - latestBollingerLower) / latestBollingerMiddle * 100);
        indicators.put("bollinger_position", close == null || latestBollingerUpper == null
                || latestBollingerLower == null || latestBollingerUpper.equals(latestBollingerLower)
                ? null : (close - latestBollingerLower) / (latestBollingerUpper - latestBollingerLower) * 100);
        indicators.put("atr14", at(atr14, latestIndex));
        indicators.put("stoch_k14", at(stochasticK14, latestIndex));
        indicators.put("stoch_d3", at(stochasticD3, latestIndex));
        indicators.put("williams_r14", at(williamsR14, latestIndex));
        indicators.put("adx14", at(adx14, latestIndex));
        indicators.put("volume_sma20", at(volumeSma20, latestIndex));
        indicators.put("obv", at(obv, latestIndex));
        indicators.put("roc12", at(roc12, latestIndex));
        analysis.put("indicators", indicators);
        analysis.put("disclaimer", "技术指标仅供研究参考，不构成投资建议");
        return analysis;
    }

    private List<Double> rollingStdDev(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i + 1 < period) {
                out.add(null);
                continue;
            }
            List<Double> window = values.subList(i - period + 1, i + 1);
            if (window.stream().anyMatch(value -> value == null)) {
                out.add(null);
                continue;
            }
            double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double variance = window.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(Double.NaN);
            out.add(Double.isFinite(variance) ? Math.sqrt(variance) : null);
        }
        return out;
    }

    private List<Double> combine(List<Double> left, List<Double> right, java.util.function.DoubleBinaryOperator operator) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            Double a = at(left, i);
            Double b = at(right, i);
            out.add(a == null || b == null ? null : operator.applyAsDouble(a, b));
        }
        return out;
    }

    private List<Double> atr(List<Map<String, Object>> rows, int period) {
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Double high = numeric(rows.get(i), "high");
            Double low = numeric(rows.get(i), "low");
            Double previousClose = i == 0 ? null : numeric(rows.get(i - 1), "close");
            if (high == null || low == null) {
                trueRanges.add(null);
            } else if (previousClose == null) {
                trueRanges.add(high - low);
            } else {
                trueRanges.add(Math.max(high - low,
                        Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose))));
            }
        }
        return sma(trueRanges, period);
    }

    private List<Double> stochasticK(List<Double> closes, List<Double> highs, List<Double> lows, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            if (i + 1 < period) {
                out.add(null);
                continue;
            }
            List<Double> highWindow = highs.subList(i - period + 1, i + 1);
            List<Double> lowWindow = lows.subList(i - period + 1, i + 1);
            Double close = closes.get(i);
            if (close == null || highWindow.stream().anyMatch(value -> value == null)
                    || lowWindow.stream().anyMatch(value -> value == null)) {
                out.add(null);
                continue;
            }
            double highest = highWindow.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            double lowest = lowWindow.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            out.add(highest == lowest ? 50.0 : (close - lowest) / (highest - lowest) * 100);
        }
        return out;
    }

    private List<Double> williamsR(List<Double> closes, List<Double> highs, List<Double> lows, int period) {
        List<Double> stochastic = stochasticK(closes, highs, lows, period);
        return stochastic.stream().map(value -> value == null ? null : value - 100).toList();
    }

    private List<Double> adx(List<Map<String, Object>> rows, int period) {
        List<Double> trueRanges = new ArrayList<>();
        List<Double> plusDirectional = new ArrayList<>();
        List<Double> minusDirectional = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Double high = numeric(rows.get(i), "high");
            Double low = numeric(rows.get(i), "low");
            if (i == 0) {
                trueRanges.add(high == null || low == null ? null : high - low);
                plusDirectional.add(0.0);
                minusDirectional.add(0.0);
                continue;
            }
            Double previousHigh = numeric(rows.get(i - 1), "high");
            Double previousLow = numeric(rows.get(i - 1), "low");
            Double previousClose = numeric(rows.get(i - 1), "close");
            if (high == null || low == null || previousHigh == null || previousLow == null || previousClose == null) {
                trueRanges.add(null);
                plusDirectional.add(null);
                minusDirectional.add(null);
                continue;
            }
            trueRanges.add(Math.max(high - low,
                    Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose))));
            double upMove = high - previousHigh;
            double downMove = previousLow - low;
            plusDirectional.add(upMove > downMove && upMove > 0 ? upMove : 0.0);
            minusDirectional.add(downMove > upMove && downMove > 0 ? downMove : 0.0);
        }
        List<Double> dx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (i + 1 < period || trueRanges.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)
                    || plusDirectional.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)
                    || minusDirectional.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)) {
                dx.add(null);
                continue;
            }
            double trSum = trueRanges.subList(i - period + 1, i + 1).stream().mapToDouble(Double::doubleValue).sum();
            double plusSum = plusDirectional.subList(i - period + 1, i + 1).stream().mapToDouble(Double::doubleValue).sum();
            double minusSum = minusDirectional.subList(i - period + 1, i + 1).stream().mapToDouble(Double::doubleValue).sum();
            double plusDi = trSum == 0 ? 0 : plusSum / trSum * 100;
            double minusDi = trSum == 0 ? 0 : minusSum / trSum * 100;
            double denominator = plusDi + minusDi;
            dx.add(denominator == 0 ? 0 : Math.abs(plusDi - minusDi) / denominator * 100);
        }
        return sma(dx, period);
    }

    private List<Double> obv(List<Double> closes, List<Double> volumes) {
        List<Double> out = new ArrayList<>();
        double value = 0;
        for (int i = 0; i < closes.size(); i++) {
            Double close = closes.get(i);
            Double volume = volumes.get(i);
            if (i > 0 && close != null && closes.get(i - 1) != null && volume != null) {
                if (close > closes.get(i - 1)) value += volume;
                else if (close < closes.get(i - 1)) value -= volume;
            }
            out.add(close == null ? null : value);
        }
        return out;
    }

    private List<Double> rateOfChange(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i < period || values.get(i) == null || values.get(i - period) == null || values.get(i - period) == 0) {
                out.add(null);
            } else {
                out.add((values.get(i) - values.get(i - period)) / values.get(i - period) * 100);
            }
        }
        return out;
    }

    private List<Double> sma(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i + 1 < period || values.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)) {
                out.add(null);
            } else {
                out.add(values.subList(i - period + 1, i + 1).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
            }
        }
        return out;
    }

    private List<Double> ema(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        double multiplier = 2.0 / (period + 1);
        Double previous = null;
        for (Double value : values) {
            if (value == null) {
                out.add(null);
                continue;
            }
            previous = previous == null ? value : (value - previous) * multiplier + previous;
            out.add(previous);
        }
        return out;
    }

    private List<Double> rsi(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) out.add(null);
        if (values.size() <= period) return out;
        double gains = 0;
        double losses = 0;
        for (int i = 1; i <= period; i++) {
            Double change = change(values, i);
            if (change == null) return out;
            if (change >= 0) gains += change;
            else losses -= change;
        }
        double averageGain = gains / period;
        double averageLoss = losses / period;
        out.set(period, rsiValue(averageGain, averageLoss));
        for (int i = period + 1; i < values.size(); i++) {
            Double change = change(values, i);
            if (change == null) continue;
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            averageGain = (averageGain * (period - 1) + gain) / period;
            averageLoss = (averageLoss * (period - 1) + loss) / period;
            out.set(i, rsiValue(averageGain, averageLoss));
        }
        return out;
    }

    private Double rsiValue(double averageGain, double averageLoss) {
        if (averageLoss == 0) return 100.0;
        return 100.0 - 100.0 / (1 + averageGain / averageLoss);
    }

    private Double change(List<Double> values, int index) {
        if (index <= 0 || index >= values.size() || values.get(index) == null || values.get(index - 1) == null) {
            return null;
        }
        return values.get(index) - values.get(index - 1);
    }

    private Double numeric(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double at(List<Double> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    private void putIfPresent(Map<String, Object> row, String key, Double value) {
        if (value != null && Double.isFinite(value)) row.put(key, value);
    }

    private Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private Double number(JsonNode node, String field) {
        return node != null && node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    private Double textDouble(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return parseDouble(value);
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String[] values, int index) {
        return index < values.length ? parseDouble(values[index]) : null;
    }

    private ZoneId zoneFor(String market) {
        return "us_stock".equals(market) ? NEW_YORK_ZONE : SHANGHAI_ZONE;
    }

    private boolean inAnySession(LocalTime current, LocalTime... boundaries) {
        for (int i = 0; i + 1 < boundaries.length; i += 2) {
            if (!current.isBefore(boundaries[i]) && current.isBefore(boundaries[i + 1])) return true;
        }
        return false;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException upstream(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private record Instrument(String market, String symbol, String name, String currency, String source) {
        private Instrument withName(String resolvedName) {
            return new Instrument(market, symbol, resolvedName, currency, source);
        }
    }
}
