package com.jarvis.research.service;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.market.dto.DailyKlineDTO;
import com.jarvis.research.market.dto.KlineBarDTO;
import com.jarvis.research.market.dto.KlineRangeDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacktestServiceTest {

    @Test
    void sellTradeKeepsActualQuantityAndReturnsStableMetrics() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        LocalDate start = LocalDate.of(2026, 1, 1);

        // 先上涨触发买入，再下跌触发卖出。
        double[] closes = {
                10, 10, 10, 10, 10,
                11, 12, 13, 14, 15,
                14, 13, 12, 11, 10,
                9, 8, 8, 8, 8
        };
        List<KlineBarDTO> bars = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            bars.add(new KlineBarDTO(start.plusDays(i).toString(), null, closes[i], null, null, 0.0));
        }
        when(marketDataService.getDailyKline("gold_etf", 20))
                .thenReturn(dailyKline(bars));

        BacktestService service = new BacktestService(marketDataService);
        Map<String, Object> result = service.run("gold_etf", 3, 5, 100000.0, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trades = (List<Map<String, Object>>) result.get("trades");
        assertFalse(trades.isEmpty());
        Map<String, Object> sell = trades.stream()
                .filter(t -> "SELL".equals(t.get("type")))
                .findFirst()
                .orElseThrow();
        assertTrue(((Number) sell.get("qty")).doubleValue() > 0.0,
                "SELL 成交数量不能在清仓后被记录成0");
        assertTrue(Double.isFinite(((Number) result.get("annual_return_pct")).doubleValue()));
        assertEquals(20, ((Number) ((Map<?, ?>) result.get("range")).get("bars")).intValue());
        assertEquals("double-ma-v1", result.get("strategy_version"));
        assertTrue(String.valueOf(result.get("data_fingerprint")).startsWith("sha256:"));
        assertEquals("2026-01-20", result.get("as_of"));
        assertNotNull(result.get("drawdown_curve"));
        assertNotNull(result.get("sharpe_ratio"));
        assertNotNull(result.get("win_rate_pct"));
        assertNotNull(result.get("profit_loss_ratio"));
        assertNotNull(result.get("avg_holding_days"));
        assertEquals(1, ((Number) result.get("completed_trades")).intValue());
    }

    @Test
    void supportsAsOfBoundaryForReproducibleRuns() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        List<KlineBarDTO> bars = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            bars.add(new KlineBarDTO("2026-02-0" + i, null, 10.0 + i, null, null, 0.0));
        }
        when(marketDataService.getDailyKline("gold_etf", 5, "2026-02-05"))
                .thenReturn(dailyKline(bars));

        BacktestService service = new BacktestService(marketDataService);
        Map<String, Object> result = service.run(
                "gold_etf", 2, 3, 100000.0, 5, "2026-02-05");

        assertEquals("2026-02-05", result.get("as_of"));
        assertEquals("2026-02-05", ((Map<?, ?>) result.get("params")).get("as_of"));
        assertTrue(String.valueOf(result.get("data_fingerprint")).startsWith("sha256:"));
    }

    @Test
    void rejectsInvalidMovingAverageParameters() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        BacktestService service = new BacktestService(marketDataService);
        assertThrows(IllegalArgumentException.class,
                () -> service.run("gold_etf", 20, 5, 100000.0, 120));
    }

    /** 日K信封桩：回测只用每根的 date/close，其余字段给 null 即可。 */
    private static DailyKlineDTO dailyKline(List<KlineBarDTO> bars) {
        String min = bars.isEmpty() ? null : bars.get(0).date();
        String max = bars.isEmpty() ? null : bars.get(bars.size() - 1).date();
        return new DailyKlineDTO("gold_etf", new KlineRangeDTO(min, max, bars.size()),
                max, bars.size(), bars);
    }
}
