package com.jarvis.research.market.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Market Data Core 数据源抽象。
 *
 * Provider 只负责从外部市场获取数据并标准化，**不负责**用户、权限、数据库、
 * 缓存、熔断与遥测——那些属于 {@code MarketDataService} 的业务稳定层。
 * 业务层通过该接口屏蔽不同行情源差异。
 *
 * 契约分层（{@code name/supports/quote/kline} 为已冻结方法，不要改名或改签名）：
 *   - {@link #name()}           稳定标识，用于日志与默认 sourceKey 推导
 *   - {@link #supports(String)} 该 Provider 是否承接此市场的行情
 *   - {@link #quote(String)}    实时行情；失败时返回含 {@code error} 键的 Map
 *   - {@link #kline(String, String, int)} K线；无数据返回空列表
 *
 * 扩展能力（均带默认实现，既有 Provider 无需改动）：
 *   - {@link #priority()}        同一市场的多个 Provider 按此升序组成降级链
 *   - {@link #sourceKey(String)} 熔断/遥测归属键，保证运行时可观测性稳定
 *   - {@link #displayName()}     面向用户的来源标签（与内部键区分）
 *   - {@link #supportsQuote(String)} 是否提供该市场的实时行情
 *   - {@link #supportsKline(String)} 是否提供该市场的K线
 */
public interface MarketDataProvider {

    /** 默认优先级；数字越小越优先。主源用 10 段，降级源用 20+ 段。 */
    int DEFAULT_PRIORITY = 100;

    /**
     * Provider 标识。
     */
    String name();

    /**
     * 是否支持指定市场。
     */
    boolean supports(String market);

    /**
     * 获取实时行情。
     */
    Map<String, Object> quote(String symbol);

    /**
     * 获取K线。
     */
    List<Map<String, Object>> kline(String symbol, String interval, int limit);

    /**
     * 同一市场的降级顺序；数字越小越先被尝试。
     *
     * 需要区分优先级的是**同一条链上互相竞争**的 Provider；若两个 Provider 因
     * {@link #supportsQuote(String)} / {@link #supportsKline(String)} 能力互补而永不共链
     * （例如伦敦金的 Yahoo 实时报价与 Sina 日K），优先级相同不影响链序确定性——
     * {@link ProviderRegistry} 对完全同序者按 name 兜底排序。
     */
    default int priority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * 该 Provider 在指定市场的熔断器/遥测归属键。
     *
     * 覆盖此方法可保持既有键名不变（例如 {@code core.tencent.etf}），
     * 避免重构把运维已依赖的指标名改掉。
     */
    default String sourceKey(String market) {
        return "core." + name().toLowerCase(Locale.ROOT) + "." + market;
    }

    /** 面向用户的来源标签；默认与 {@link #name()} 一致。 */
    default String displayName() {
        return name();
    }

    /**
     * 该 Provider 是否提供指定市场的实时行情。
     *
     * 用于「只提供K线」的来源（例如新浪伦敦金日K）：返回 false 时业务层不会把它
     * 排进实时行情降级链，也就不会为它产生无意义的熔断失败。
     */
    default boolean supportsQuote(String market) {
        return true;
    }

    /**
     * 该 Provider 是否提供指定市场的K线。
     *
     * 用于「实时行情主源」与「K线主源」不是同一家的情况：例如伦敦金实时行情走腾讯，
     * 而日K来自新浪。返回 false 时业务层不会为此 Provider 记熔断失败。
     */
    default boolean supportsKline(String market) {
        return true;
    }
}