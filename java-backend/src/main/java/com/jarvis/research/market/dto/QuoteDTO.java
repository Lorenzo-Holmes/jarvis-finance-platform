package com.jarvis.research.market.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 行情报价（{@code /api/market/prices} 与 {@code /api/market/prices/stream} 的对外形状）。
 *
 * <h2>为什么字段全部可空</h2>
 * 本 DTO 是「行情源产出的键集」的**并集**，不是所有来源都会给全字段：
 * <ul>
 *   <li>腾讯：symbol/name/price/prev_close/open/high/low/change/change_pct/source_quote_time</li>
 *   <li>东方财富：同上但**没有** {@code source_quote_time}</li>
 *   <li>Yahoo：只有 symbol/price/prev_close/change/change_pct/source_quote_time，**没有** name/open/high/low</li>
 *   <li>数据库兜底（{@code latestPrice}）：没有 source / source_quote_time / received_at</li>
 * </ul>
 * 因此类级用 {@link JsonInclude.Include#NON_NULL}：**来源没提供的键就不出现**，
 * 这与迁移前 Map 的行为一致；否则稀疏来源会凭空多出一堆 {@code null} 键、改变契约。
 *
 * <h2>{@code status} 的用途</h2>
 * 闭市时 {@code refreshLivePrices} 会在缓存里放一个占位对象
 * {@code {"status":"market_closed"}}。占位对象与真实报价共用一个字段类型，
 * 靠 NON_NULL 自然退化成 {@code {status, stale}} 两个键——这正是迁移前的编码方式。
 *
 * <h2>与内部表示的关系</h2>
 * {@code MarketDataProvider.quote()} 的签名按约定冻结为 {@code Map}，且积存金、扩展市场
 * 两个子系统同样以 Map 流通、模拟交易侧对三者统一处理，所以**内部货币仍是 Map**。
 * 本 DTO 只在面向前端的边界上做适配，{@link #from(Map)} 是唯一的转换点，
 * 其保真性由 {@code MarketApiContractTest} 逐形状比对钉住。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteDTO(
        String market,
        String symbol,
        String name,
        Double price,
        @JsonProperty("prev_close") Double prevClose,
        Double open,
        Double high,
        Double low,
        Double change,
        @JsonProperty("change_pct") Double changePct,
        @JsonProperty("source_quote_time") String sourceQuoteTime,
        String source,
        @JsonProperty("quote_time") String quoteTime,
        @JsonProperty("received_at") String receivedAt,
        Boolean stale,
        String status) {

    /** 闭市占位对象的 status 取值。 */
    public static final String STATUS_MARKET_CLOSED = "market_closed";

    /**
     * 内部 Map 表示 → 对外 DTO。**唯一的转换点**。
     *
     * 只认上面列出的已知字段：行情源若新增了未知键，它会被丢弃。这是有意的——
     * 对外契约应当由本 DTO 定义，而不是"行情源返回了什么就透传什么"。
     */
    public static QuoteDTO from(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return new QuoteDTO(
                text(raw.get("market")),
                text(raw.get("symbol")),
                text(raw.get("name")),
                number(raw.get("price")),
                number(raw.get("prev_close")),
                number(raw.get("open")),
                number(raw.get("high")),
                number(raw.get("low")),
                number(raw.get("change")),
                number(raw.get("change_pct")),
                text(raw.get("source_quote_time")),
                text(raw.get("source")),
                text(raw.get("quote_time")),
                text(raw.get("received_at")),
                flag(raw.get("stale")),
                text(raw.get("status")));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 数值字段一律归一成 Double。
     *
     * 行情源实际都发数值（腾讯 {@code number()}、东方财富 {@code /1000.0}、Yahoo 直接算），
     * 这里额外容忍可解析的字符串，但解析不出来就是 null——保持"宁可缺也不要假 0"的既有口径。
     */
    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code stale} 只接受真正的布尔或可识别的字符串，其余为 null。 */
    private static Boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        return null;
    }
}