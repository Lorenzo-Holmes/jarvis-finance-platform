package com.jarvis.research.ai;

import com.jarvis.research.market.ExtendedMarketDataService;
import com.jarvis.research.market.dto.KlineBarDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MarketDataGatewayAdapter} 的行为：调对方法、规整对数据、不把异常扔出去。
 *
 * <p>这里 stub 的是真实的 {@link ExtendedMarketDataService}（Mockito 可以 mock 具体类），
 * 所以断言的是"这个适配器与那个服务的实际契约是否对得上"，
 * 而不是一个我自己编出来的接口。</p>
 */
class MarketDataGatewayAdapterTest {

    private final ExtendedMarketDataService service = mock(ExtendedMarketDataService.class);
    private final MarketDataGatewayAdapter adapter = new MarketDataGatewayAdapter(service);

    @Test
    void dailyBarsGoesThroughTheDailyIntervalAndTheGivenLimit() {
        when(service.kline(eq("a_share"), eq("sh600519"), eq("1d"), anyInt()))
                .thenReturn(envelope(row("2026-09-16", 10, 11, 12, 9, 100)));

        List<KlineBarDTO> bars = adapter.dailyBars("a_share", "sh600519", 120);

        assertEquals(1, bars.size());
        assertEquals("2026-09-16", bars.get(0).date());
        assertEquals(10.0, bars.get(0).open());
        assertEquals(11.0, bars.get(0).close());
        assertEquals(12.0, bars.get(0).high());
        assertEquals(9.0, bars.get(0).low());
        assertEquals(100.0, bars.get(0).volume());
    }

    /**
     * 行里的数字可能是 {@code Double}（行情直出），也可能是字符串（落库路径）。
     * 两种都要认，否则同一段逻辑会因为数据来源不同而静默丢掉数字。
     */
    @Test
    void numericValuesAreAcceptedAsNumbersOrStrings() {
        Map<String, Object> asNumbers = row("2026-09-16", 10, 11, 12, 9, 100);
        Map<String, Object> asStrings = new LinkedHashMap<>();
        asStrings.put("date", "2026-09-17");
        asStrings.put("open", "10.5");
        asStrings.put("close", "11.5");
        asStrings.put("high", "12.5");
        asStrings.put("low", "9.5");
        asStrings.put("volume", "200");

        when(service.kline(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(envelope(asNumbers, asStrings));

        List<KlineBarDTO> bars = adapter.dailyBars("a_share", "sh600519", 120);

        assertEquals(2, bars.size());
        assertEquals(11.5, bars.get(1).close(), "字符串数字要认");
        assertEquals(200.0, bars.get(1).volume());
    }

    /** 解析不出来的值留 null，**那一行仍然保留**——丢不丢由指标层决定。 */
    @Test
    void unparsableValuesBecomeNullInsteadOfDroppingTheRow() {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("date", "2026-09-16");
        broken.put("open", "-");
        broken.put("close", "11.0");
        broken.put("high", "");
        broken.put("low", null);
        broken.put("volume", "abc");

        when(service.kline(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(envelope(broken));

        List<KlineBarDTO> bars = adapter.dailyBars("a_share", "sh600519", 120);

        assertEquals(1, bars.size());
        assertNull(bars.get(0).open());
        assertNull(bars.get(0).high());
        assertNull(bars.get(0).low());
        assertNull(bars.get(0).volume());
        assertEquals(11.0, bars.get(0).close(), "收盘价能用就还能算指标");
    }

    @Test
    void aMissingOrMalformedEnvelopeYieldsNoBars() {
        when(service.kline(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(null)
                .thenReturn(Map.of())
                .thenReturn(Map.of("data", "not-a-list"));

        assertTrue(adapter.dailyBars("a_share", "sh600519", 120).isEmpty());
        assertTrue(adapter.dailyBars("a_share", "sh600519", 120).isEmpty());
        assertTrue(adapter.dailyBars("a_share", "sh600519", 120).isEmpty());
    }

    /** 行列表里混进非 Map 元素时跳过它，而不是整段失败。 */
    @Test
    void nonMapRowsAreSkipped() {
        List<Object> rows = new ArrayList<>();
        rows.add(row("2026-09-16", 10, 11, 12, 9, 100));
        rows.add("garbage");
        when(service.kline(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("data", rows));

        assertEquals(1, adapter.dailyBars("a_share", "sh600519", 120).size());
    }

    /** 服务抛异常（例如降级链全失败）时返回空，不让异常冒到研究任务里。 */
    @Test
    void serviceFailuresBecomeEmptyResultsNotExceptions() {
        when(service.kline(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("K线源全部失败"));
        when(service.quote(anyString(), anyString()))
                .thenThrow(new IllegalStateException("上游 502"));

        assertTrue(adapter.dailyBars("a_share", "sh600519", 120).isEmpty());
        assertNull(adapter.quote("a_share", "sh600519"));
    }

    @Test
    void quoteIsPassedThroughUntouched() {
        Map<String, Object> quote = Map.of("price", 121.0, "source", "Tencent");
        when(service.quote("a_share", "sh600519")).thenReturn(quote);

        assertEquals(quote, adapter.quote("a_share", "sh600519"),
                "信封由服务层定义，适配器不该改写它");
    }

    // ==================== 夹具 ====================

    @SafeVarargs
    private static Map<String, Object> envelope(Map<String, Object>... rows) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("market", "a_share");
        envelope.put("interval", "1d");
        envelope.put("data", List.of(rows));
        return envelope;
    }

    private static Map<String, Object> row(String date, double open, double close,
                                          double high, double low, double volume) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("open", open);
        row.put("close", close);
        row.put("high", high);
        row.put("low", low);
        row.put("volume", volume);
        return row;
    }
}