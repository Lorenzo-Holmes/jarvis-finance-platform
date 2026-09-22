package com.jarvis.research.agent;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResearchContextTest {

    @Test
    void clientContextIsNormalizedAndBoundToOneInstrument() {
        AgentResearchContext context = AgentResearchContext.from(Map.of(
                "market", "A_SHARE", "symbol", "sh600519", "name", "贵州茅台"));

        assertEquals("a_share", context.market());
        assertEquals("a_share:sh600519", context.key());
        assertTrue(context.matches("贵州茅台盈利质量分析"));
        assertTrue(context.matches("SH600519 的估值区间"));
        assertFalse(context.matches("黄金 ETF 的短期趋势"));
    }

    @Test
    void missingContextKeepsTheLegacyGoldDefault() {
        assertEquals(AgentResearchContext.DEFAULT, AgentResearchContext.from(null));
        assertEquals(AgentResearchContext.DEFAULT, AgentResearchContext.persisted(null, null, null));
    }

    @Test
    void unsupportedMarketsAndUnsafeSymbolsAreRejected() {
        assertThrows(ResponseStatusException.class, () -> AgentResearchContext.from(Map.of(
                "market", "unknown", "symbol", "ABC", "name", "test")));
        assertThrows(ResponseStatusException.class, () -> AgentResearchContext.from(Map.of(
                "market", "a_share", "symbol", "<script>", "name", "test")));
        assertThrows(ResponseStatusException.class, () -> AgentResearchContext.from(Map.of(
                "market", "us_stock", "symbol", "A".repeat(65), "name", "test")));
    }

    @Test
    void acceptsOverviewGoldContextWithoutRebindingItToAnotherAsset() {
        AgentResearchContext context = AgentResearchContext.from(Map.of(
                "market", "sge_gold", "symbol", "Au99.99", "name", "黄金9999"));

        assertEquals("sge_gold:Au99.99", context.key());
        assertFalse(context.isExtendedMarket());
        assertTrue(context.isSgeGoldMarket());
    }

    @Test
    void jdGoldAndGlobalIndexContextsAreRestrictedToSupportedInstruments() {
        AgentResearchContext jd = AgentResearchContext.from(Map.of(
                "market", "jd_gold", "symbol", "JD-ZS-GOLD", "name", "浙商积存金"));
        assertTrue(jd.isJdGoldMarket());
        assertEquals("jd_zheshang", jd.jdGoldSourceSymbol());

        AgentResearchContext index = AgentResearchContext.from(Map.of(
                "market", "global_index", "symbol", "^GSPC", "name", "标普500"));
        assertTrue(index.supportsExtendedKline());

        assertThrows(ResponseStatusException.class, () -> AgentResearchContext.from(Map.of(
                "market", "jd_gold", "symbol", "jd_arbitrary", "name", "invalid")));
        assertThrows(ResponseStatusException.class, () -> AgentResearchContext.from(Map.of(
                "market", "global_index", "symbol", "AAPL", "name", "not an index")));
    }
}
