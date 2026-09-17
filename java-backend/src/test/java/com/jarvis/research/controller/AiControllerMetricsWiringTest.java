package com.jarvis.research.controller;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.market.dto.DailyKlineDTO;
import com.jarvis.research.market.dto.KlineBarDTO;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import com.jarvis.research.service.FeaturePermissionService;
import com.jarvis.research.service.SimTradeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ⑧ chat 面接线的 Java 侧证据：转发给 Python 的 payload 里必须真的带上 metrics，
 * 且 K 线 DTO 转 Map 之后的键名必须是 Python 实际读取的那几个。
 *
 * <p>为什么要专门钉键名：DailyKlineDTO 是 record，字段经 Jackson 序列化后的名字取决于
 * 全局命名策略。这里手写成 Map，一旦键名写错，Python 读到的是空值——**不会报错**，
 * 只会让两边的数值悄悄分叉。夹具取 3 根 K 线，支撑/阻力分别为 95 与 107，可手算。
 */
class AiControllerMetricsWiringTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static DailyKlineDTO threeBars() {
        List<KlineBarDTO> bars = List.of(
                new KlineBarDTO("2026-01-02", 99.0, 100.0, 105.0, 95.0, 11.0),
                new KlineBarDTO("2026-01-03", 100.0, 101.0, 106.0, 96.0, 12.0),
                new KlineBarDTO("2026-01-04", 101.0, 102.0, 107.0, 97.0, 13.0));
        return new DailyKlineDTO("gold_etf", null, "2026-01-04", bars.size(), bars);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Map<String, Object> forwardedChatBody() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        FeaturePermissionService permissions = mock(FeaturePermissionService.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        SimTradeService simTradeService = mock(SimTradeService.class);
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of("price", 7.88)));
        when(marketDataService.getDailyKline(any(), eq(60))).thenReturn(threeBars());
        when(simTradeService.getAccountOverview(42L)).thenReturn(Map.of(
                "cash", 90000, "initialCash", 90000, "positions", Map.of()));
        when(proxy.post(eq("/api/ai/chat"), any())).thenReturn(Map.of("code", 200));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        new AiController(proxy, rateLimit, permissions, marketDataService, simTradeService)
                .chat(Map.of("messages", List.of(Map.of("role", "user", "content", "今天金价怎么看"))));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(proxy).post(eq("/api/ai/chat"), bodyCaptor.capture());
        return asMap(bodyCaptor.getValue());
    }

    @Test
    void chatForwardsDeterministicMetricsAlongsideTheContext() {
        Map<String, Object> body = forwardedChatBody();

        assertTrue(body.containsKey("metrics"), "chat 必须下发 Java 算好的确定性上下文");
        Map<String, Object> metrics = asMap(body.get("metrics"));
        assertEquals(4, metrics.size(), "上下文只有 generated_at/quotes/indicators/portfolio 四段");
        assertNotNull(metrics.get("generated_at"));
    }

    @Test
    void forwardedMetricsCarryQuotesComputedByJava() {
        Map<String, Object> metrics = asMap(forwardedChatBody().get("metrics"));

        Map<String, Object> quote = asMap(asMap(metrics.get("quotes")).get("gold_etf"));
        assertEquals("7.880000", quote.get("price"), "价格按 6 位小数定标");
    }

    @Test
    void forwardedMetricsCarryKlineIndicatorsComputedByJava() {
        Map<String, Object> metrics = asMap(forwardedChatBody().get("metrics"));

        Map<String, Object> indicator = asMap(asMap(metrics.get("indicators")).get("gold_etf"));
        assertEquals(true, indicator.get("available"));
        assertEquals(3, indicator.get("bars"));
        assertEquals("95.000000", indicator.get("support20"), "支撑取 K 线最低价的最小值");
        assertEquals("107.000000", indicator.get("resistance20"), "阻力取 K 线最高价的最大值");
    }

    @Test
    void forwardedMetricsCarryPortfolioComputedByJava() {
        Map<String, Object> metrics = asMap(forwardedChatBody().get("metrics"));

        Map<String, Object> portfolio = asMap(metrics.get("portfolio"));
        assertEquals(true, portfolio.get("available"));
        assertEquals("90000.000000", portfolio.get("cash"));
    }

    @Test
    void klineDtoIsConvertedToTheKeysPythonActuallyReads() {
        Map<String, Object> context = asMap(forwardedChatBody().get("research_context"));

        Map<String, Object> kline = asMap(asMap(context.get("klines")).get("gold_etf"));
        assertEquals("2026-01-04", kline.get("as_of"), "as_of 是带注解的字段，序列化名是下划线");
        assertEquals(3, kline.get("count"));
        List<Map<String, Object>> rows = asList(kline.get("data"));
        assertEquals(3, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals(6, first.size(), "每根 K 线必须是 Python 读取的 6 个键");
        for (String key : List.of("date", "open", "close", "high", "low", "volume")) {
            assertTrue(first.containsKey(key), "缺少 Python 会读取的键: " + key);
        }
        assertEquals(100.0, first.get("close"));
        assertEquals(95.0, first.get("low"));
        assertEquals(105.0, first.get("high"));
    }
}