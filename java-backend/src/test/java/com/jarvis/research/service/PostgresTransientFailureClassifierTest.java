package com.jarvis.research.service;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTransientFailureClassifierTest {

    private final PostgresTransientFailureClassifier classifier = new PostgresTransientFailureClassifier();

    @Test
    void recognizesDeadlockAndLockTimeoutSqlStatesThroughWrappedCause() {
        assertTrue(classifier.isTransient(new RuntimeException(new SQLException("deadlock", "40P01"))));
        assertTrue(classifier.isTransient(new RuntimeException(new SQLException("lock timeout", "55P03"))));
        assertTrue(classifier.isTransient(new RuntimeException(new SQLException("serialization", "40001"))));
    }

    @Test
    void doesNotRetryBusinessValidationOrUniqueConstraint() {
        assertFalse(classifier.isTransient(new IllegalArgumentException("invalid order")));
        assertFalse(classifier.isTransient(new SQLException("duplicate", "23505")));
    }
}
