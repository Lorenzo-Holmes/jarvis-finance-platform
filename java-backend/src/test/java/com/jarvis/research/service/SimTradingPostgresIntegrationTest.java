package com.jarvis.research.service;

import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.user.SimAccount;
import com.jarvis.research.user.SimAccountRepository;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTradeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL-specific transaction/locking gate for the simulated trading core.
 *
 * <p>The regular integration suite uses H2 for fast local feedback. This suite is enabled only in CI
 * with RUN_PG_IT=true so the production database's SELECT ... FOR UPDATE and transaction semantics
 * are also exercised before merge.</p>
 */
@EnabledIfEnvironmentVariable(named = "RUN_PG_IT", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${PG_IT_URL:jdbc:postgresql://127.0.0.1:5432/jarvis_ci}",
        "spring.datasource.username=${PG_IT_USERNAME:jarvis}",
        "spring.datasource.password=${PG_IT_PASSWORD:jarvis_ci_password}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=postgres-integration-test-jwt-secret-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000",
        "jarvis.market.live-poll-interval-ms=3600000",
        "jarvis.jd.live-poll-interval-ms=3600000"
})
class SimTradingPostgresIntegrationTest {

    @Autowired private SimTradeService tradeService;
    @Autowired private SimRiskService riskService;
    @Autowired private SimAccountRepository accountRepository;
    @Autowired private SimPositionRepository positionRepository;
    @Autowired private SimTradeRepository tradeRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    @MockBean private MarketDataService marketDataService;
    @MockBean private JdGoldService jdGoldService;

    @AfterEach
    void cleanDatabase() {
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        accountRepository.deleteAll();
        auditEventRepository.deleteAll();
    }

    @Test
    void postgresPessimisticLockPreventsConcurrentOverspend() throws Exception {
        long userId = 91001L;
        accountRepository.save(account(userId, "100000.0000"));
        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", liveQuote("100.00")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> competingOrder(userId, "pg-concurrent-1", ready, start));
            Future<Boolean> second = executor.submit(() -> competingOrder(userId, "pg-concurrent-2", ready, start));
            ready.await();
            start.countDown();

            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes, "PostgreSQL 下两笔并发 6 万订单只能有一笔穿过账户悲观锁");
        } finally {
            executor.shutdownNow();
        }

        SimAccount result = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals(0, result.getCash().compareTo(new BigDecimal("40000.0000")));
        assertEquals(1L, tradeRepository.countByUserId(userId));
        assertEquals(0, positionRepository.findByUserIdAndSymbol(userId, "sh518850").orElseThrow()
                .getQuantity().compareTo(new BigDecimal("600.00000000")));
    }

    @Test
    void postgresConcurrentSameClientOrderIdIsExactlyOnce() throws Exception {
        long userId = 91500L;
        accountRepository.save(account(userId, "1000.0000"));
        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", liveQuote("10.00")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Map<String, Object>> first = executor.submit(() -> retrySameOrder(userId, ready, start));
            Future<Map<String, Object>> second = executor.submit(() -> retrySameOrder(userId, ready, start));
            ready.await();
            start.countDown();

            Map<String, Object> firstResult = first.get();
            Map<String, Object> secondResult = second.get();
            int replayCount = (Boolean.TRUE.equals(firstResult.get("idempotentReplay")) ? 1 : 0)
                    + (Boolean.TRUE.equals(secondResult.get("idempotentReplay")) ? 1 : 0);
            assertEquals(1, replayCount, "PostgreSQL 并发重试同一订单时必须 exactly-once");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, tradeRepository.countByUserId(userId));
        assertEquals(0, accountRepository.findByUserId(userId).orElseThrow().getCash()
                .compareTo(new BigDecimal("900.0000")));
        assertEquals(0, positionRepository.findByUserIdAndSymbol(userId, "sh518850").orElseThrow()
                .getQuantity().compareTo(new BigDecimal("10.00000000")));
    }

    @Test
    void postgresRiskLiquidationCommitsAccountPositionTradeAndAuditTogether() {
        long userId = 92002L;
        accountRepository.save(account(userId, "1000.0000"));
        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", liveQuote("10.00")));
        tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("500"), new BigDecimal("5"), "pg-risk-seed");

        when(marketDataService.getLatestPrices()).thenReturn(Map.of("gold_etf", liveQuote("8.40")));
        riskService.checkAndLiquidate();

        SimAccount result = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals("FROZEN", result.getStatus());
        assertEquals(0, result.getCash().compareTo(new BigDecimal("200.0000")));
        assertEquals(0, result.getLoanBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.getFrozenMargin().compareTo(BigDecimal.ZERO));
        assertTrue(positionRepository.findAllByUserId(userId).isEmpty());
        assertEquals(2L, tradeRepository.countByUserId(userId));
        assertTrue(auditEventRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, org.springframework.data.domain.PageRequest.of(0, 10)).stream()
                .anyMatch(event -> "FORCE_LIQUIDATION".equals(event.getAction())));
    }

    private Map<String, Object> retrySameOrder(long userId,
                                               CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("10"), BigDecimal.ONE, "pg-same-network-retry");
    }

    private boolean competingOrder(long userId, String orderId,
                                    CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            tradeService.placeOrder(userId, "BUY", "sh518850",
                    new BigDecimal("600"), BigDecimal.ONE, orderId);
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
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

    private Map<String, Object> liveQuote(String price) {
        return Map.of(
                "price", price,
                "quote_time", LocalDateTime.now().toString(),
                "stale", false
        );
    }
}
