package com.jarvis.research.service;

import com.jarvis.research.audit.AuditEventRepository;
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
import org.springframework.dao.CannotAcquireLockException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证锁冲突重试发生在事务边界外，第二次尝试能够拿到全新的事务。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sim-retry;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=retry-integration-test-jwt-secret-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000"
})
class SimTradeRetryIntegrationTest {

    @Autowired private SimTradeService tradeService;
    @MockBean private SimAccountRepository accountRepository;
    @Autowired private SimPositionRepository positionRepository;
    @Autowired private SimTradeRepository tradeRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    @MockBean private MarketDataService marketDataService;
    @MockBean private JdGoldService jdGoldService;

    @AfterEach
    void cleanDatabase() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        auditEventRepository.deleteAll();
    }

    @Test
    void retriesLockConflictWithFreshTransaction() {
        long userId = 82001L;
        SimAccount account = SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("1000.0000"))
                .cash(new BigDecimal("1000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
        when(accountRepository.findByUserIdForUpdate(userId))
                .thenThrow(new CannotAcquireLockException("injected lock conflict"))
                .thenReturn(Optional.of(account));
        when(accountRepository.save(org.mockito.ArgumentMatchers.any(SimAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of("price", "10.00", "quote_time", LocalDateTime.now().toString(), "stale", false)));
        tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("10"), BigDecimal.ONE, "retry-lock-order");

        verify(accountRepository, org.mockito.Mockito.times(2)).findByUserIdForUpdate(userId);
        assertEquals(1L, tradeRepository.countByUserId(userId));
        assertEquals(0, account.getCash()
                .compareTo(new BigDecimal("900.0000")));
    }
}
