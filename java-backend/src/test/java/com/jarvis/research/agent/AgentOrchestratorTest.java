package com.jarvis.research.agent;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentOrchestratorTest {

    @Test
    void eventProtocolKeepsRunIdentityAndAllowsNullPayloadValues() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rsi14", null);
        AgentEvent raw = AgentEvent.create(
                "tool_result", "completed", "指标完成", "TechnicalIndicatorTool",
                "gold_etf", "结果已生成", payload,
                Instant.now(), Instant.now(), 12L, null);

        AgentEvent normalized = raw.withRun("run-1", 4L);

        assertEquals("run-1", normalized.runId());
        assertEquals(4L, normalized.sequence());
        assertNull(normalized.payload().get("rsi14"));
    }

    @Test
    void cancellationStopsBeforeReadingMarketData() {
        MarketDataService marketData = mock(MarketDataService.class);
        AiProxyService aiProxy = mock(AiProxyService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                marketData, aiProxy, new AiRateLimitService(), new AgentToolRegistry());
        List<AgentEvent> events = new ArrayList<>();

        orchestrator.run(7L, "run-2", "分析黄金", events::add, () -> true);

        assertTrue(events.isEmpty());
        verifyNoInteractions(marketData, aiProxy);
    }
}
