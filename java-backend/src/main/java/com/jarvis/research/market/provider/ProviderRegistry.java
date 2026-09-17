package com.jarvis.research.market.provider;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Market Data Provider 注册中心。
 *
 * 职责：把「市场 → 一组有序 Provider」的解析收敛到一处，让
 * {@code MarketDataService} 只依赖抽象而不认识任何具体行情源。
 *
 * 后续新增市场时只需要新增 Provider 并声明 {@code supports}/{@code priority}，
 * 不需要修改核心行情服务。
 */
@Component
public class ProviderRegistry {

    /** 同一市场内按优先级升序；优先级相同再按 name 排序，保证链序确定。 */
    private static final Comparator<MarketDataProvider> BY_PRIORITY =
            Comparator.comparingInt(MarketDataProvider::priority)
                    .thenComparing(MarketDataProvider::name);

    private final List<MarketDataProvider> providers;

    public ProviderRegistry(List<MarketDataProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    /**
     * 指定市场的**降级链**：按 priority 升序，先主源后备用源。
     *
     * 市场无人承接时返回空列表——调用方据此决定是报错还是转本地兜底，
     * 而不是被迫捕获异常。
     */
    public List<MarketDataProvider> chain(String market) {
        if (market == null) {
            return List.of();
        }
        String normalized = market.trim().toLowerCase(Locale.ROOT);
        return providers.stream()
                .filter(provider -> provider.supports(normalized))
                .sorted(BY_PRIORITY)
                .toList();
    }

    /** 指定市场的实时行情候选链；已剔除明确声明不提供实时行情的 Provider。 */
    public List<MarketDataProvider> quoteChain(String market) {
        return filtered(market, MarketDataProvider::supportsQuote);
    }

    /** 指定市场的K线候选链；已剔除明确声明不提供该市场K线的 Provider。 */
    public List<MarketDataProvider> klineChain(String market) {
        return filtered(market, MarketDataProvider::supportsKline);
    }

    /**
     * 按市场**与周期**筛出可用的K线 Provider。
     *
     * <p>周期为 null/空白时退化成 {@link #klineChain(String)}。入参是来源要取的周期
     * （10 分钟由服务层聚合，查链时给的是 5m），理由见
     * {@link MarketDataProvider#supportsKline(String, String)}。</p>
     */
    public List<MarketDataProvider> klineChain(String market, String interval) {
        if (interval == null || interval.isBlank()) {
            return klineChain(market);
        }
        String normalizedInterval = interval.trim().toLowerCase(Locale.ROOT);
        return filtered(market, (provider, normalizedMarket) ->
                provider.supportsKline(normalizedMarket, normalizedInterval));
    }

    private List<MarketDataProvider> filtered(
            String market, java.util.function.BiPredicate<MarketDataProvider, String> capability) {
        if (market == null) {
            return List.of();
        }
        String normalized = market.trim().toLowerCase(Locale.ROOT);
        return chain(normalized).stream()
                .filter(provider -> capability.test(provider, normalized))
                .toList();
    }

    /** 指定市场的主源；无承接者时为空。 */
    public Optional<MarketDataProvider> findOptional(String market) {
        return chain(market).stream().findFirst();
    }

    /**
     * 指定市场的主源，无承接者时抛异常。
     *
     * @throws IllegalArgumentException 该市场没有任何 Provider 承接
     */
    public MarketDataProvider find(String market) {
        return findOptional(market).orElseThrow(() -> new IllegalArgumentException(
                "No market data provider for: " + market));
    }

    /** 已注册的 Provider 数量（诊断/测试用）。 */
    public int size() {
        return providers.size();
    }
}