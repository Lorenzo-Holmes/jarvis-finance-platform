package com.jarvis.research.service;

import com.jarvis.research.audit.AuditEvent;
import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.user.SimAccount;
import com.jarvis.research.user.SimAccountRepository;
import com.jarvis.research.user.SimPosition;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTradeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sim-integration;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=integration-test-jwt-secret-key-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000"
})
class SimTradingIntegrationTest {

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
    void pessimisticAccountLockPreventsConcurrentOverspend() throws Exception {
        long userId = 1001L;
        accountRepository.save(SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("100000.0000"))
                .cash(new BigDecimal("100000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", liveQuote("100.00")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> placeCompetingOrder(userId, "concurrent-1", ready, start));
            Future<Boolean> second = executor.submit(() -> placeCompetingOrder(userId, "concurrent-2", ready, start));
            ready.await();
            start.countDown();

            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes, "两笔 6 万订单同时抢 10 万现金时只能有一笔成交");
        } finally {
            executor.shutdownNow();
        }

        SimAccount account = accountRepository.findByUserId(userId).orElseThrow();
        SimPosition position = positionRepository.findByUserIdAndSymbol(userId, "sh518850").orElseThrow();
        assertEquals(0, account.getCash().compareTo(new BigDecimal("40000.0000")));
        assertEquals(0, position.getQuantity().compareTo(new BigDecimal("600.00000000")));
        assertEquals(1L, tradeRepository.countByUserId(userId));
    }

    @Test
    void riskScanForceLiquidatesReachableFiveTimesPositionAtomically() {
        long userId = 2002L;
        accountRepository.save(SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("1000.0000"))
                .cash(new BigDecimal("1000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());

        // 先按真实业务入口建立允许上限 5x 的仓位：500 * 10 = 5000，保证金 1000，借款 4000。
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", liveQuote("10.00")));
        tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("500"), new BigDecimal("5"), "risk-seed-order");

        SimAccount leveraged = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals(0, leveraged.getCash().compareTo(new BigDecimal("0.0000")));
        assertEquals(0, leveraged.getLoanBalance().compareTo(new BigDecimal("4000.0000")));
        assertEquals(0, leveraged.getFrozenMargin().compareTo(new BigDecimal("1000.0000")));

        // 跌至 8.40 后市值 4200、净权益 200，维持保证金率约 4.76%，低于 15% 强平线。
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", liveQuote("8.40")));
        riskService.checkAndLiquidate();

        SimAccount account = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals("FROZEN", account.getStatus());
        assertEquals(0, account.getCash().compareTo(new BigDecimal("200.0000")));
        assertEquals(0, account.getLoanBalance().compareTo(new BigDecimal("0.0000")));
        assertEquals(0, account.getFrozenMargin().compareTo(new BigDecimal("0.0000")));
        assertTrue(positionRepository.findAllByUserId(userId).isEmpty());

        var trades = tradeRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10));
        assertEquals(2, trades.size());
        assertEquals("FORCE_SELL", trades.get(0).getType());
        assertEquals(0, trades.get(0).getPrice().compareTo(new BigDecimal("8.40000000")));
        assertEquals("BUY", trades.get(1).getType());

        List<AuditEvent> audit = auditEventRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10));
        assertTrue(audit.stream().anyMatch(event -> "FORCE_LIQUIDATION".equals(event.getAction())));
        assertTrue(audit.stream().anyMatch(event -> "SIM_ORDER".equals(event.getAction())));
    }

    @Test
    void concurrentRetryWithSameClientOrderIdIsExactlyOnce() throws Exception {
        long userId = 2400L;
        accountRepository.save(SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("1000.0000"))
                .cash(new BigDecimal("1000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", liveQuote("10.00")));

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
            assertEquals(1, replayCount, "同一个 clientOrderId 并发重试时应有一次真实成交、一次幂等重放");
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
    void clientOrderIdReplayIsExactlyOnceAndParameterMismatchConflicts() {
        long userId = 2500L;
        accountRepository.save(SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("1000.0000"))
                .cash(new BigDecimal("1000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", liveQuote("10.00")));

        Map<String, Object> first = tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("10"), BigDecimal.ONE, "retry-safe-order");
        Map<String, Object> replay = tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("10.00000000"), BigDecimal.ONE, "retry-safe-order");

        assertEquals(false, first.get("idempotentReplay"));
        assertEquals(true, replay.get("idempotentReplay"));
        SimAccount account = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals(0, account.getCash().compareTo(new BigDecimal("900.0000")));
        assertEquals(1L, tradeRepository.countByUserId(userId));
        assertEquals(0, positionRepository.findByUserIdAndSymbol(userId, "sh518850").orElseThrow()
                .getQuantity().compareTo(new BigDecimal("10.00000000")));

        var conflict = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> tradeService.placeOrder(userId, "BUY", "sh518850",
                        new BigDecimal("11"), BigDecimal.ONE, "retry-safe-order"));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(1L, tradeRepository.countByUserId(userId));
    }

    @Test
    void staleCrashQuoteCannotTriggerForcedLiquidation() {
        long userId = 3003L;
        accountRepository.save(SimAccount.builder()
                .userId(userId)
                .initialCash(new BigDecimal("1000.0000"))
                .cash(new BigDecimal("1000.0000"))
                .loanBalance(BigDecimal.ZERO)
                .frozenMargin(BigDecimal.ZERO)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build());
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", liveQuote("10.00")));
        tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("500"), new BigDecimal("5"), "stale-risk-seed");

        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of(
                        "price", "1.00",
                        "quote_time", LocalDateTime.now().minusMinutes(10).toString(),
                        "stale", true
                )));
        riskService.checkAndLiquidate();

        SimAccount account = accountRepository.findByUserId(userId).orElseThrow();
        assertEquals("ACTIVE", account.getStatus());
        assertEquals(1, positionRepository.findAllByUserId(userId).size());
        var trades = tradeRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10));
        assertEquals(1, trades.size());
        assertEquals("BUY", trades.get(0).getType());
    }

    private Map<String, Object> retrySameOrder(long userId,
                                               CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return tradeService.placeOrder(userId, "BUY", "sh518850",
                new BigDecimal("10"), BigDecimal.ONE, "same-network-retry");
    }

    private boolean placeCompetingOrder(long userId, String clientOrderId,
                                         CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            tradeService.placeOrder(userId, "BUY", "sh518850",
                    new BigDecimal("600"), BigDecimal.ONE, clientOrderId);
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    private Map<String, Object> liveQuote(String price) {
        return Map.of(
                "price", price,
                "quote_time", LocalDateTime.now().toString(),
                "stale", false
        );
    }
}
