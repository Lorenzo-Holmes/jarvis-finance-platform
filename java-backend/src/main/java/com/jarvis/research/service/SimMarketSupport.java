package com.jarvis.research.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/** 模拟盘支持的标的市场推断与报价时间处理。 */
final class SimMarketSupport {

    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern A_SHARE = Pattern.compile("^(SH|SZ|BJ)\\d{6}$");
    private static final Pattern CRYPTO = Pattern.compile("^[A-Z0-9]{2,11}USDT$");
    private static final Pattern US_STOCK = Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");

    private SimMarketSupport() {
    }

    /**
     * 保留早期模拟盘的内部行情映射。它们不属于多市场自定义标的，但仍需兼容已有持仓。
     */
    static String legacyMarket(String symbol) {
        if (symbol == null) return null;
        return switch (symbol.trim()) {
            case "sh518850" -> "gold_etf";
            case "hf_XAU" -> "london_gold";
            case "jd_zheshang" -> "jd_zheshang";
            case "jd_minsheng" -> "jd_minsheng";
            default -> null;
        };
    }

    /** 返回标准化自选市场；无法安全判断时返回 null。 */
    static String market(String symbol) {
        if (symbol == null) return null;
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (A_SHARE.matcher(normalized).matches()) return "a_share";
        if (CRYPTO.matcher(normalized).matches()) return "crypto";
        if (US_STOCK.matcher(normalized).matches()) return "us_stock";
        return null;
    }

    static LocalDateTime quoteTime(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        if (value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            // continue with offset/instant formats returned by Yahoo Finance.
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception ignored) {
            // continue with UTC instant format.
        }
        try {
            return Instant.parse(value).atZone(SHANGHAI_ZONE).toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    static long maxAgeSeconds(String market) {
        return market != null && market.startsWith("jd_") ? 180L : 120L;
    }
}
