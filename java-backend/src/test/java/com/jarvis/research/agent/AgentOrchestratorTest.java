package com.jarvis.research.agent;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.market.dto.DailyKlineDTO;
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

    @Test
    void eachToolPublishesLifecycleEventsOnOneStepId() {
        MarketDataService marketData = mock(MarketDataService.class);
        AiProxyService aiProxy = mock(AiProxyService.class);
        when(aiProxy.post(startsWith("/internal/rss/digest"), any()))
                .thenReturn(Map.of(
                        "articles", List.of(),
                        "sources", List.of(),
                        "generated_at", "2026-09-21T00:00:00Z",
                        "total_sources", 0,
                        "ok_sources", 0));
        when(aiProxy.post(eq("/api/ai/chat"), any()))
                .thenReturn(Map.of("data", Map.of("content", "研究结论")));
        when(marketData.getLatestPrices())
                .thenReturn(Map.of("gold_etf", Map.of("price", 1.0)));
        when(marketData.getDailyKline("gold_etf", 60))
                .thenReturn(new DailyKlineDTO("gold_etf", null, "2026-09-20", 0, List.of()));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                marketData, aiProxy, new AiRateLimitService(), new AgentToolRegistry());
        List<AgentEvent> events = new ArrayList<>();

        orchestrator.run(7L, "run-lifecycle", "分析黄金", events::add, () -> false);

        assertTrue(events.stream().anyMatch(event -> "run_completed".equals(event.type())),
                () -> "Agent should complete, events=" + events);
        List<AgentEvent> toolCalls = events.stream()
                .filter(event -> "tool_call".equals(event.type()))
                .toList();
        assertFalse(toolCalls.isEmpty());
        for (AgentEvent toolCall : toolCalls) {
            List<AgentEvent> sameStep = events.stream()
                    .filter(event -> toolCall.stepId().equals(event.stepId()))
                    .toList();
            assertTrue(sameStep.stream().anyMatch(event -> "step_started".equals(event.type())));
            assertTrue(sameStep.stream().anyMatch(event -> "step_completed".equals(event.type())));
            AgentEvent completed = sameStep.stream()
                    .filter(event -> "step_completed".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(completed.durationMs());
        }
    }
}
