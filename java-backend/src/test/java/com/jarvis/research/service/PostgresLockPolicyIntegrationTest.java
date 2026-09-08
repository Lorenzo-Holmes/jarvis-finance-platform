package com.jarvis.research.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 专项锁策略测试。仅在 CI/专用 PostgreSQL 测试库执行，避免误连生产库。
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
        "jarvis.jwt.secret=postgres-lock-policy-test-secret-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000",
        "jarvis.market.live-poll-interval-ms=3600000",
        "jarvis.jd.live-poll-interval-ms=3600000"
})
class PostgresLockPolicyIntegrationTest {

    private static final String TABLE = "jarvis_lock_policy_probe";

    @Autowired private DataSource dataSource;

    @BeforeEach
    void prepareProbeTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (id INTEGER PRIMARY KEY, touched INTEGER NOT NULL DEFAULT 0)");
        jdbc.update("DELETE FROM " + TABLE);
        jdbc.update("INSERT INTO " + TABLE + " (id, touched) VALUES (1, 0), (2, 0)");
    }

    @AfterEach
    void dropProbeTable() {
        new JdbcTemplate(dataSource).execute("DROP TABLE IF EXISTS " + TABLE);
    }

    @Test
    void lockTimeoutReturnsPostgres55P03() throws Exception {
        try (Connection holder = dataSource.getConnection();
             Connection contender = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            contender.setAutoCommit(false);
            execute(holder, "SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE");
            execute(contender, "SET LOCAL lock_timeout = '200ms'");

            SQLException failure = null;
            try {
                execute(contender, "SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE");
            } catch (SQLException expected) {
                failure = expected;
            }
            assertNotNull(failure);
            assertEquals("55P03", failure.getSQLState());
        } finally {
            // try-with-resources 会关闭并回滚未提交事务。
        }
    }

    @Test
    void twoConnectionsProducePostgres40P01Deadlock() throws Exception {
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            execute(first, "SET LOCAL lock_timeout = '0'");
            execute(second, "SET LOCAL lock_timeout = '0'");
            execute(first, "SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE");
            execute(second, "SELECT id FROM " + TABLE + " WHERE id = 2 FOR UPDATE");

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<SQLException> firstFailure = executor.submit(() -> updateAfter(start, first, 2));
                Future<SQLException> secondFailure = executor.submit(() -> updateAfter(start, second, 1));
                start.countDown();
                SQLException one = firstFailure.get(5, TimeUnit.SECONDS);
                SQLException two = secondFailure.get(5, TimeUnit.SECONDS);
                boolean deadlockDetected = hasSqlState(one, "40P01") || hasSqlState(two, "40P01");
                assertTrue(deadlockDetected, "应至少有一个事务收到 PostgreSQL 40P01");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private SQLException updateAfter(CountDownLatch start, Connection connection, int id) throws Exception {
        start.await();
        try {
            execute(connection, "UPDATE " + TABLE + " SET touched = touched + 1 WHERE id = " + id);
            return null;
        } catch (SQLException failure) {
            return failure;
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean hasSqlState(SQLException failure, String sqlState) {
        return failure != null && sqlState.equals(failure.getSQLState());
    }
}
