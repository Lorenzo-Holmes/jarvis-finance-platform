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
 *   - {@link #quote(String, String)} 市场感知的行情入口（默认委托给单参版本）
 *   - {@link #priority()}        同一市场的多个 Provider 按此升序组成降级链
 *   - {@link #sourceKey(String)} 熔断/遥测归属键，保证运行时可观测性稳定
 *   - {@link #klineSourceKey(String)} K线专用的熔断/遥测键（默认同 sourceKey）
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
     * 市场感知的实时行情入口；默认忽略市场、委托给 {@link #quote(String)}。
     *
     * <p>为什么需要它：同一家行情源在不同市场下的**响应口径可能不同**，而单看标的无法区分。
     * 腾讯即是例子——黄金ETF 与 A 股的字段下标完全相同，只有「是否带 {@code source_quote_time}」
     * 这一处不同，靠标的形如 {@code sh518850} / {@code sh600519} 是分不出来的。
     * 按标的分派会把两个口径混为一谈。</p>
     *
     * <p>既有 Provider 无需改动：默认实现即原行为。也正因如此，
     * {@link #quote(String)} 保持冻结、不删不改。</p>
     */
    default Map<String, Object> quote(String market, String symbol) {
        return quote(symbol);
    }

    /**
     * K线的熔断/遥测归属键；默认与 {@link #sourceKey(String)} 相同。
     *
     * <p>为什么需要单独一个：同一家厂商的**实时行情与K线往往是两个独立来源**，
     * 运维也是分开统计的。腾讯 A股就是例子——实时走 {@code qt.gtimg.cn}
     * （键 {@code extended.tencent.stock}），日K走 {@code web.ifzq.gtimg.cn}
     * （键 {@code extended.tencent.kline}）。用同一个键会把两条链路的成功率、
     * 熔断状态混在一起，一边坏掉会连带切断另一边。</p>
     */
    default String klineSourceKey(String market) {
        return sourceKey(market);
    }

    /**
     * 市场感知的K线入口；默认忽略市场、委托给 {@link #kline(String, String, int)}。
     *
     * <p>与 {@link #quote(String, String)} 同样的理由：同一家行情源在不同市场下可能需要
     * 不同的标的映射或解析口径，而单看标的并不总能可靠区分。
     * Yahoo 就是例子——美股要 {@code BRK.B → BRK-B}，加密货币要 {@code BTCUSDT → BTC-USD}。</p>
     */
    default List<Map<String, Object>> kline(String market, String symbol, String interval, int limit) {
        return kline(symbol, interval, limit);
    }

    /**
     * 单次K线请求的来源端条数上限；默认 1000。
     *
     * <p>用途：服务层把 10 分钟周期用 5 分钟数据聚合时，需要按来源的上限决定一次取多少原始K线。
     * 下限是真实存在的——Yahoo 的 chart 接口对分钟级一次最多返回 500 条，Binance 是 1000。
     * 用统一上限会让某一侧的多取或少取，聚合出来的根数与限流行为都会变。</p>
     */
    default int maxKlineLimit() {
        return 1000;
    }

    /**
     * 该市场的K线是否参与熔断；默认参与。
     *
     * <p>默认值即重构前的实际行为：A股日K与加密货币K线本来就走熔断。
     * 唯独美股日K**不走**，而这不是疏漏——它的熔断键是 {@code extended.yahoo.stock}，
     * 与美股**报价**共用。一旦让K线的失败去打开这个键，一次K线故障会把实时行情一起切断，
     * 而两者只是同一接口的不同参数，故障面并不相同。所以这里保留差别，而不是"顺手统一"。</p>
     */
    default boolean klineUsesCircuitBreaker(String market) {
        return true;
    }

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

    /**
     * 该市场**该周期**下本 Provider 是否能取到K线；默认与 {@link #supportsKline(String)} 一致。
     *
     * <p>为什么需要它：A股日K的链是"腾讯 → 东方财富"，而分钟级只有东方财富能做
     * （腾讯不提供分钟K）。如果链只按市场过滤，腾讯会被选进分钟级的链里、
     * 返回空、然后被记一次熔断失败——而它的熔断键 {@code extended.tencent.kline}
     * 与**日K共用**，于是一次分钟级请求的失败会把日K一起打断。
     * 声明能力时必须把周期带上，否则注册表选出来的链和真实能力对不上。</p>
     *
     * <p>注意入参是**来源要取的周期**：10 分钟由服务层用 5 分钟聚合，
     * 所以查链时用的是 5m 而不是 10m。Provider 只对来源真正能给的周期负责。</p>
     */
    default boolean supportsKline(String market, String interval) {
        return supportsKline(market);
    }
}