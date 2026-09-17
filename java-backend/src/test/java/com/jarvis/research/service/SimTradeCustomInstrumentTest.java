package com.jarvis.research.service;

import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.market.ExtendedMarketDataService;
import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.market.dto.MarketStatusDTO;
import com.jarvis.research.user.SimAccount;
import com.jarvis.research.user.SimAccountRepository;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTradeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证模拟盘可以交易多市场自选标的，并在收市时拒绝新成交。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sim-custom;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=custom-instrument-test-jwt-secret-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000"
})
class SimTradeCustomInstrumentTest {

    @Autowired private SimTradeService tradeService;
    @Autowired private SimAccountRepository accountRepository;
    @Autowired private SimPositionRepository positionRepository;
    @Autowired private SimTradeRepository tradeRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    @MockBean private MarketDataService marketDataService;
    @MockBean private ExtendedMarketDataService extendedMarketDataService;

    @AfterEach
    void cleanDatabase() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        accountRepository.deleteAll();
        auditEventRepository.deleteAll();
    }

    @Test
    void tradesCustomUsStockWhenMarketIsOpen() {
        long userId = 73001L;
        accountRepository.save(account(userId));
        when(extendedMarketDataService.session("us_stock"))
                .thenReturn(marketStatus(true, "交易中"));
        when(extendedMarketDataService.quote("us_stock", "AAPL"))
                .thenReturn(Map.of(
                        "market", "us_stock", "symbol", "AAPL", "price", 200.0,
                        "quote_time", LocalDateTime.now().toString(), "stale", false));

        Map<String, Object> result = tradeService.placeOrder(userId, "BUY", "AAPL",
                new BigDecimal("2"), BigDecimal.ONE, "custom-us-order");

        assertEquals("AAPL", result.get("symbol"));
        assertEquals(1L, tradeRepository.countByUserId(userId));
        assertEquals(0, accountRepository.findByUserId(userId).orElseThrow().getCash()
                .compareTo(new BigDecimal("99600.0000")));
        verify(extendedMarketDataService).session("us_stock");
        verify(extendedMarketDataService).quote("us_stock", "AAPL");
    }

    @Test
    void rejectsCustomUsStockWhenMarketIsClosed() {
        long userId = 73002L;
        accountRepository.save(account(userId));
        when(extendedMarketDataService.session("us_stock"))
                .thenReturn(marketStatus(false, "非交易时段"));

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> tradeService.placeOrder(userId, "BUY", "AAPL",
                        new BigDecimal("2"), BigDecimal.ONE, "closed-us-order"));

        assertEquals(409, error.getStatusCode().value());
        assertEquals(0L, tradeRepository.countByUserId(userId));
        assertEquals(0, accountRepository.findByUserId(userId).orElseThrow().getCash()
                .compareTo(new BigDecimal("100000.0000")));
    }

    private SimAccount account(long userId) {
        return SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("100000.0000"))
                .cash(new BigDecimal("100000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** 交易时段桩：本次只关心 is_open 与 label，其余字段给稳定值即可。 */
    private static MarketStatusDTO marketStatus(boolean open, String label) {
        return new MarketStatusDTO("us_stock", open, open ? "open" : "closed", label,
                "America/New_York", LocalDateTime.now().toString(), "测试桩");
    }
}
