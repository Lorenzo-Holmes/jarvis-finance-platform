package com.jarvis.research.market;

import com.jarvis.research.market.provider.MarketDataProvider;
import com.jarvis.research.market.provider.ProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场数据服务 (Java 主管数据存储)
 * - 实时存取黄金ETF + 伦敦金价格 (PriceSnapshot)
 * - 每日抓取一次K线 (KlineDaily)
 * - 根据实时价格生成分钟K线 (1/5/15/30/60分)
 */
@Slf4j
@Service
public class MarketDataService {

    /** 超过该年龄的内存 tick 不再落库，避免上游断流时把旧价格伪装成新行情。 */
    private static final long MAX_LIVE_PERSIST_AGE_SECONDS = 10L;
    private static final ZoneId QUOTE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TENCENT_COMPACT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter SPACE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每日K线一次落库的条数上限；与重构前各行情源请求的 500 根保持一致。 */
    private static final int DAILY_KLINE_LIMIT = 500;

    private final PriceSnapshotRepository snapshotRepo;
    private final KlineDailyRepository klineRepo;
    private final MarketSourceCircuitBreaker circuitBreaker;
    private final ProviderRegistry providerRegistry;
    @Autowired(required = false)
    private MarketTelemetry telemetry;
    /** 秒级实时报价只保存在内存；数据库仍按较低频率落快照，避免长期产生海量 tick 行。 */
    private final Map<String, Map<String, Object>> livePriceCache = new ConcurrentHashMap<>();

    /** 不依赖 Spring 的最小构造，供单元测试直连使用（无熔断器、无 Provider）。 */
    public MarketDataService(PriceSnapshotRepository snapshotRepo, KlineDailyRepository klineRepo) {
        this(snapshotRepo, klineRepo, null, null);
    }

    /**
     * 业务稳定层依赖。
     *
     * 行情源的 URL、协议与字段解析已全部下沉到 {@code market.provider} 包，
     * 因此这里不再需要 {@code JarvisProperties} / {@code ObjectMapper} / {@code WebClient}。
     */
    @Autowired
    public MarketDataService(PriceSnapshotRepository snapshotRepo,
                             KlineDailyRepository klineRepo,
                             MarketSourceCircuitBreaker circuitBreaker,
                             ProviderRegistry providerRegistry) {
        this.snapshotRepo = snapshotRepo;
        this.klineRepo = klineRepo;
        this.circuitBreaker = circuitBreaker;
        this.providerRegistry = providerRegistry;
    }

    // ==================== 实时价格 ====================

    /**
     * 秒级实时报价采集。只刷新内存缓存，不按秒写数据库；这样前端可获得 1s 价格，
     * 同时避免 price_snapshot 长期以每秒一行的速度膨胀。
     */
    @Scheduled(initialDelay = 1000, fixedDelayString = "${jarvis.market.live-poll-interval-ms:1000}")
    public void pollLivePrices() {
        if (telemetry != null) telemetry.recordPollCycle("core");
        refreshLivePrices();
    }

    /** 数据库快照仍按较低频率持久化，供分钟 K、回测和故障兜底使用。 */
    @Scheduled(initialDelay = 5000, fixedDelayString = "${jarvis.market.persist-interval-ms:30000}")
    public void persistLivePrices() {
        persistCachedPrices();
    }

    /** 手工触发一次采集并立即持久化，保留旧调用语义。 */
    public Map<String, Object> fetchAndStorePrices() {
        Map<String, Object> out = refreshLivePrices();
        persistQuotes(out);
        return out;
    }

    /** 抓取两个标的并刷新秒级缓存。 */
    public Map<String, Object> refreshLivePrices() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (isChinaEtfTradingTime()) {
            Map<String, Object> quote = fetchOne("gold_etf", "sh518850");
            out.put("gold_etf", quote);
            if (!quote.containsKey("error")) livePriceCache.put("gold_etf", quote);
        } else {
            out.put("gold_etf", Map.of("status", "market_closed"));
        }
        if (isLondonTradingDay()) {
            Map<String, Object> quote = fetchOne("london_gold", "hf_XAU");
            out.put("london_gold", quote);
            if (!quote.containsKey("error")) livePriceCache.put("london_gold", quote);
        } else {
            out.put("london_gold", Map.of("status", "market_closed"));
        }
        return out;
    }

    private boolean isChinaEtfTradingTime() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        boolean morning = !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30));
        boolean afternoon = !time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0));
        return morning || afternoon;
    }

    private boolean isLondonTradingDay() {
        DayOfWeek day = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** API 优先读取秒级内存报价；服务刚启动尚无缓存时回退数据库最近快照。 */
    public Map<String, Object> getLatestPrices() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> etf = livePriceCache.get("gold_etf");
        Map<String, Object> london = livePriceCache.get("london_gold");
        if (etf != null) out.put("gold_etf", withCurrentFreshness(etf));
        else latestPrice("gold_etf", "sh518850", "黄金ETF华夏").ifPresent(v -> out.put("gold_etf", v));
        if (london != null) out.put("london_gold", withCurrentFreshness(london));
        else latestPrice("london_gold", "hf_XAU", "伦敦金(现货黄金)").ifPresent(v -> out.put("london_gold", v));
        return out;
    }

    private Optional<Map<String, Object>> latestPrice(String market, String symbol, String name) {
        return snapshotRepo.findTopByMarketOrderByTsDesc(market).map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("market", market);
            item.put("symbol", symbol);
            item.put("name", name);
            item.put("price", s.getPrice());
            item.put("change", s.getChange());
            item.put("change_pct", s.getChangePct());
            item.put("prev_close", s.getPrevClose());
            item.put("open", s.getOpen());
            item.put("high", s.getHigh());
            item.put("low", s.getLow());
            item.put("quote_time", s.getTs().toString());
            item.put("stale", s.getTs() == null
                    || s.getTs().isBefore(LocalDateTime.now().minusSeconds(MAX_LIVE_PERSIST_AGE_SECONDS)));
            return item;
        });
    }

    /**
     * 按 ProviderRegistry 给出的降级链逐个尝试，返回第一个成功的行情。
     *
     * 这里只保留业务稳定层职责——熔断、遥测、来源标注与陈旧判定；
     * 各行情源的 URL、协议与字段解析已下沉到 Provider，本类不认识任何一家行情源。
     */
    private Map<String, Object> fetchOne(String market, String symbol) {
        List<MarketDataProvider> chain = quoteChain(market);
        if (chain.isEmpty()) {
            return Map.of("error", "无可用行情源: " + market);
        }

        String lastError = "行情源均不可用: " + market;
        for (int index = 0; index < chain.size(); index++) {
            MarketDataProvider provider = chain.get(index);
            String source = provider.sourceKey(market);
            if (!allowSource(source)) {
                lastError = "行情源熔断中: " + source;
                continue;
            }
            try {
                Map<String, Object> quote = provider.quote(symbol);
                if (quote == null || quote.containsKey("error")) {
                    throw new IllegalStateException(quote == null
                            ? "行情源返回空结果"
                            : String.valueOf(quote.get("error")));
                }
                recordSourceSuccess(source);
                if (index > 0 && telemetry != null) {
                    telemetry.recordSourceSwitch(market, source);
                }
                return withQuoteMetadata(market, quote, provider.displayName());
            } catch (Exception e) {
                recordSourceFailure(source);
                if (telemetry != null) telemetry.recordFetchFailure(market);
                log.warn("行情源失败，尝试下一个 market={}, provider={}, source={}, message={}",
                        market, provider.name(), source, e.getMessage());
                lastError = e.getMessage() == null ? "行情源调用失败" : e.getMessage();
            }
        }
        return Map.of("error", lastError);
    }

    /** 指定市场的实时行情降级链；未注入 Registry 时为空链。 */
    private List<MarketDataProvider> quoteChain(String market) {
        if (providerRegistry == null) {
            log.warn("ProviderRegistry 未注入，无法获取行情 market={}", market);
            return List.of();
        }
        return providerRegistry.quoteChain(market);
    }

    private Map<String, Object> withQuoteMetadata(String market, Map<String, Object> quote, String source) {
        Map<String, Object> out = new LinkedHashMap<>(quote);
        LocalDateTime receivedAt = LocalDateTime.now();
        LocalDateTime sourceQuoteTime = parseSourceQuoteTime(quote.get("source_quote_time"));
        LocalDateTime effectiveQuoteTime = sourceQuoteTime == null ? receivedAt : sourceQuoteTime;
        out.put("market", market);
        out.put("source", source);
        boolean stale = effectiveQuoteTime.isBefore(receivedAt.minusSeconds(MAX_LIVE_PERSIST_AGE_SECONDS));
        out.put("quote_time", effectiveQuoteTime.toString());
        out.put("received_at", receivedAt.toString());
        out.put("stale", stale);
        if (telemetry != null) telemetry.recordQuote(market, sourceQuoteTime, stale);
        return out;
    }

    private Map<String, Object> withCurrentFreshness(Map<String, Object> quote) {
        Map<String, Object> copy = new LinkedHashMap<>(quote);
        LocalDateTime quoteTime = parseQuoteTime(copy.get("quote_time"));
        boolean stale = quoteTime == null
                || quoteTime.isBefore(LocalDateTime.now().minusSeconds(MAX_LIVE_PERSIST_AGE_SECONDS));
        copy.put("stale", stale);
        return copy;
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

    private void persistCachedPrices() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        livePriceCache.forEach((market, quote) -> snapshot.put(market, new LinkedHashMap<>(quote)));
        persistQuotes(snapshot);
    }

    @SuppressWarnings("unchecked")
    private void persistQuotes(Map<String, Object> quotes) {
        quotes.forEach((market, raw) -> {
            if (!(raw instanceof Map<?, ?> quote) || quote.containsKey("error") || quote.get("price") == null) return;
            try {
                LocalDateTime quoteTime = parseQuoteTime(quote.get("quote_time"));
                if (quoteTime == null || quoteTime.isBefore(LocalDateTime.now().minusSeconds(MAX_LIVE_PERSIST_AGE_SECONDS))) {
                    if (telemetry != null) telemetry.recordStalePersistSkip(market);
                    log.warn("跳过陈旧行情落库: market={}, quoteTime={}", market, quote.get("quote_time"));
                    return;
                }
                snapshotRepo.save(new PriceSnapshot(
                        market,
                        asDouble(quote.get("price")),
                        asDouble(quote.get("change")),
                        asDouble(quote.get("change_pct")),
                        asDouble(quote.get("prev_close")),
                        asDouble(quote.get("open")),
                        asDouble(quote.get("high")),
                        asDouble(quote.get("low")),
                        quoteTime));
            } catch (Exception e) {
                log.warn("持久化行情失败 {}: {}", market, e.getMessage());
            }
        });
    }

    private LocalDateTime parseQuoteTime(Object value) {
        if (value == null) return null;
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 优先解析行情源自身时间，避免“接口仍返回旧 tick，但服务器每秒收到 200”时把旧价伪装成新价。
     * 腾讯 A 股常见 yyyyMMddHHmmss；伦敦金接口常见 HH:mm:ss，也兼容 ISO/空格日期时间。
     */
    LocalDateTime parseSourceQuoteTime(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {}
        try {
            return LocalDateTime.parse(text, TENCENT_COMPACT_TIME);
        } catch (Exception ignored) {}
        try {
            return LocalDateTime.parse(text, SPACE_DATE_TIME);
        } catch (Exception ignored) {}
        try {
            return LocalDate.now(QUOTE_ZONE).atTime(LocalTime.parse(text));
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== 日K线 (定时刷新 + API纯查询) ====================

    /**
     * 独立刷新日K，不依赖页面访问。
     * 同一日期的数据允许覆盖，避免盘中第一次抓取后当天 OHLC 被永久冻结。
     */
    @Scheduled(initialDelay = 5000, fixedDelayString = "${jarvis.market.daily-kline-refresh-ms:1800000}")
    public void refreshDailyKlines() {
        refreshDailyKlineMarket("gold_etf", fetchKline("gold_etf", "sh518850"));
        refreshDailyKlineMarket("london_gold", fetchKline("london_gold", "hf_XAU"));
    }

    /**
     * 按 ProviderRegistry 的K线候选链取数，第一个返回非空结果者胜出。
     *
     * 伦敦金正是这种分工的例子：实时行情主源是腾讯，但腾讯不提供它的日K
     * （{@code supportsKline} 为 false），于是链上顺延到新浪。
     *
     * 与实时行情不同，K线不参与熔断——这是重构前的既有行为，本次不改变其语义。
     */
    private List<Map<String, Object>> fetchKline(String market, String symbol) {
        if (providerRegistry == null) {
            log.warn("ProviderRegistry 未注入，无法刷新K线 market={}", market);
            return List.of();
        }
        for (MarketDataProvider provider : providerRegistry.klineChain(market)) {
            try {
                List<Map<String, Object>> rows = provider.kline(symbol, "1d", DAILY_KLINE_LIMIT);
                if (rows != null && !rows.isEmpty()) {
                    return rows;
                }
                log.debug("K线源无数据，尝试下一个 market={}, provider={}", market, provider.name());
            } catch (Exception e) {
                log.warn("K线源失败，尝试下一个 market={}, provider={}, message={}",
                        market, provider.name(), e.getMessage());
            }
        }
        return List.of();
    }

    private void refreshDailyKlineMarket(String market, List<Map<String, Object>> fetched) {
        if (fetched == null || fetched.isEmpty()) return;

        Map<String, KlineDaily> existingByDate = new HashMap<>();
        for (KlineDaily existing : klineRepo.findByMarketOrderByDateAsc(market)) {
            existingByDate.put(existing.getDate(), existing);
        }

        List<KlineDaily> changed = new ArrayList<>();
        for (Map<String, Object> row : fetched) {
            String date = String.valueOf(row.getOrDefault("date", "")).trim();
            if (date.isEmpty()) continue;

            Double open = asDouble(row.get("open"));
            Double close = asDouble(row.get("close"));
            Double high = asDouble(row.get("high"));
            Double low = asDouble(row.get("low"));
            Double volume = asDouble(row.getOrDefault("volume", 0.0));
            if (open == null || close == null || high == null || low == null) continue;
            if (volume == null) volume = 0.0;

            KlineDaily entity = existingByDate.get(date);
            if (entity == null) {
                entity = new KlineDaily(market, date, open, close, high, low, volume);
                changed.add(entity);
                continue;
            }

            if (!Objects.equals(entity.getOpen(), open)
                    || !Objects.equals(entity.getClose(), close)
                    || !Objects.equals(entity.getHigh(), high)
                    || !Objects.equals(entity.getLow(), low)
                    || !Objects.equals(entity.getVolume(), volume)) {
                entity.setOpen(open);
                entity.setClose(close);
                entity.setHigh(high);
                entity.setLow(low);
                entity.setVolume(volume);
                changed.add(entity);
            }
        }

        if (!changed.isEmpty()) {
            klineRepo.saveAll(changed);
            log.info("日K刷新完成: market={}, changed={}", market, changed.size());
        }
    }

    /** API 只读数据库最近 N 根日K，不触发任何外部请求或写入。 */
    public Map<String, Object> getDailyKline(String market, int limit) {
        return getDailyKline(market, limit, null);
    }

    /** 按可选截止日读取最近 N 根日K，用于可复现回测。 */
    public Map<String, Object> getDailyKline(String market, int limit, String asOf) {
        List<KlineDaily> latest = new ArrayList<>(
                asOf == null || asOf.isBlank()
                        ? klineRepo.findByMarketOrderByDateDesc(market, PageRequest.of(0, limit))
                        : klineRepo.findByMarketAndDateLessThanEqualOrderByDateDesc(
                                market, asOf.trim(), PageRequest.of(0, limit)));
        Collections.reverse(latest);

        List<Map<String, Object>> data = new ArrayList<>();
        for (KlineDaily k : latest) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", k.getDate());
            m.put("open", k.getOpen());
            m.put("close", k.getClose());
            m.put("high", k.getHigh());
            m.put("low", k.getLow());
            m.put("volume", k.getVolume() == null ? 0.0 : k.getVolume());
            data.add(m);
        }

        Map<String, Object> rng = new LinkedHashMap<>();
        rng.put("min", data.isEmpty() ? null : data.get(0).get("date"));
        rng.put("max", data.isEmpty() ? null : data.get(data.size() - 1).get("date"));
        rng.put("count", data.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("range", rng);
        out.put("as_of", data.isEmpty() ? asOf : data.get(data.size() - 1).get("date"));
        out.put("count", data.size());
        out.put("data", data);
        return out;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 分钟K线 (基于实时价格快照) ====================

    /**
     * 根据 PriceSnapshot 生成分钟K线
     * @param market 标的
     * @param minutes 分钟数 (1/5/15/30/60)
     * @param limit 返回根数
     */
    public Map<String, Object> getMinuteKline(String market, int minutes, int limit) {
        // 只读取生成所需 K 线的大致快照量，避免运行越久每次请求扫描越多历史数据。
        int samplesPerMinute = market.startsWith("jd_") ? 1 : 2;
        long estimatedRows = (long) limit * minutes * samplesPerMinute * 2L;
        int rowsToRead = (int) Math.max(500L, Math.min(100_000L, estimatedRows));
        List<PriceSnapshot> snaps = new ArrayList<>(
                snapshotRepo.findByMarketOrderByTsDesc(market, PageRequest.of(0, rowsToRead)));
        Collections.reverse(snaps);
        // 按分钟桶聚合
        Map<String, List<PriceSnapshot>> buckets = new TreeMap<>();
        for (PriceSnapshot s : snaps) {
            String bucket = bucketKey(s.getTs(), minutes);
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, List<PriceSnapshot>> e : buckets.entrySet()) {
            List<PriceSnapshot> list = e.getValue();
            PriceSnapshot first = list.get(0);
            PriceSnapshot last = list.get(list.size() - 1);
            double open = first.getPrice();
            double close = last.getPrice();
            double high = list.stream().mapToDouble(PriceSnapshot::getPrice).max().orElse(close);
            double low = list.stream().mapToDouble(PriceSnapshot::getPrice).min().orElse(close);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("date", e.getKey());
            k.put("open", open);
            k.put("close", close);
            k.put("high", high);
            k.put("low", low);
            k.put("volume", 0.0);
            data.add(k);
        }
        int from = Math.max(0, data.size() - limit);
        List<Map<String, Object>> sliced = new ArrayList<>(data.subList(from, data.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("interval", minutes + "m");
        out.put("count", sliced.size());
        out.put("data", sliced);
        return out;
    }

    private String bucketKey(LocalDateTime ts, int minutes) {
        int total = ts.getHour() * 60 + ts.getMinute();
        int bucket = (total / minutes) * minutes;
        return ts.toLocalDate() + " " + String.format("%02d:%02d", bucket / 60, bucket % 60);
    }
}
