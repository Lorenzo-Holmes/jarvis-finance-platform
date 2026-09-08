package com.jarvis.research.service;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * PostgreSQL 并发失败分类器。
 *
 * <p>PostgreSQL 的 40P01（deadlock detected）和 55P03（lock_not_available）
 * 经过 Hibernate/Spring 异常转换后可能落在不同的包装层，因此同时检查
 * SQLState 和 Spring/JPA 的锁异常类型。</p>
 */
@Component
public class PostgresTransientFailureClassifier {

    private static final Set<String> RETRYABLE_SQL_STATES = Set.of(
            "40P01", // deadlock detected
            "55P03", // lock_not_available / lock_timeout
            "40001"  // serialization_failure
    );

    public boolean isTransient(Throwable failure) {
        if (failure == null) return false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sql
                    && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) {
                return true;
            }
            if (current instanceof DeadlockLoserDataAccessException
                    || current instanceof CannotAcquireLockException
                    || current instanceof PessimisticLockingFailureException
                    || current instanceof CannotSerializeTransactionException
                    || current instanceof PessimisticLockException
                    || current instanceof LockTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
