package com.jarvis.research.service;

import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.audit.AuditService;
import com.jarvis.research.market.MarketDataService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 故障注入：在业务写入完成、审计写入前后抛出异常，验证同一事务内的资金、持仓、成交记录全部回滚。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sim-rollback;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=rollback-integration-test-jwt-secret-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000"
})
class SimTradingRollbackFaultInjectionTest {

    @Autowired private SimTradeService tradeService;
    @Autowired private SimRiskService riskService;
    @Autowired private SimAccountRepository accountRepository;
    @Autowired private SimPositionRepository positionRepository;
    @Autowired private SimTradeRepository tradeRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    @MockBean private AuditService auditService;
    @MockBean private MarketDataService marketDataService;
    @MockBean private JdGoldService jdGoldService;

    @AfterEach
    void cleanDatabase() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        accountRepository.deleteAll();
        auditEventRepository.deleteAll();
        reset(auditService, marketDataService, jdGoldService);
    }

    @Test
    void orderFailureAfterAccountPositionAndTradeWritesRollsBackEverything() {
        long userId = 81001L;
        accountRepository.save(account(userId, "1000.0000"));
        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", quote("10.00")));
        doThrow(new IllegalStateException("injected audit failure"))
                .when(auditService).record(anyLong(), anyString(), anyString(), any(), anyString());

        assertThrows(IllegalStateException.class, () -> tradeService.placeOrder(
                userId, "BUY", "sh518850", new BigDecimal("10"), BigDecimal.ONE, "rollback-order"));

        SimAccount result = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals(0, result.getCash().compareTo(new BigDecimal("1000.0000")));
        assertEquals(0, result.getLoanBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.getFrozenMargin().compareTo(BigDecimal.ZERO));
        assertEquals(0, positionRepository.findAllByUserId(userId).size());
        assertEquals(0L, tradeRepository.countByUserId(userId));
        assertEquals(0, auditEventRepository.findByUserIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void liquidationFailureAfterTradeAndAccountWritesRollsBackLiquidation() {
        long userId = 81002L;
        accountRepository.save(account(userId, "1000.0000"));
        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", quote("10.00")));
        doNothing().when(auditService).record(anyLong(), anyString(), anyString(), any(), anyString());
        tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("500"), new BigDecimal("5"), "rollback-risk-seed");

        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", quote("8.40")));
        doThrow(new IllegalStateException("injected liquidation audit failure"))
                .when(auditService).record(anyLong(), anyString(), anyString(), any(), anyString());

        // 风控任务按账户隔离并吞掉单账户异常；断言异常事务没有留下半套强平结果。
        riskService.checkAndLiquidate();

        SimAccount result = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(0, result.getCash().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.getLoanBalance().compareTo(new BigDecimal("4000.0000")));
        assertEquals(1, positionRepository.findAllByUserId(userId).size());
        assertEquals(1L, tradeRepository.countByUserId(userId));
    }

    private SimAccount account(long userId, String cash) {
        BigDecimal amount = new BigDecimal(cash);
        return SimAccount.builder()
                .userId(userId)
                .initialCash(amount)
                .cash(amount)
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Map<String, Object> quote(String price) {
        return Map.of("price", price, "quote_time", LocalDateTime.now().toString(), "stale", false);
    }
}
