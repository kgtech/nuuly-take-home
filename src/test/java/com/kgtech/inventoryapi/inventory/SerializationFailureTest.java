package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

/** Y2: retry only when the most specific cause is an SQLException with SQLState 40001 or 40P01. Plain unit test. */
class SerializationFailureTest {

    private final SerializationFailure predicate = new SerializationFailure();

    private static Method method() throws NoSuchMethodException {
        return InventoryService.class.getMethod("add", String.class, int.class, String.class);
    }

    private static PessimisticLockingFailureException lockFailure(String sqlState) {
        return new CannotAcquireLockException("x", new SQLException("x", sqlState));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001", "40P01"})
    void retriesSerializationFailureAndDeadlock(String sqlState) throws Exception {
        assertThat(predicate.shouldRetry(method(), lockFailure(sqlState))).isTrue();
    }

    @Test
    void doesNotRetryLockNotAvailable() throws Exception {
        assertThat(predicate.shouldRetry(method(), lockFailure("55P03"))).isFalse();
    }

    @Test
    void doesNotRetryNullSqlState() throws Exception {
        assertThat(predicate.shouldRetry(method(), lockFailure(null))).isFalse();
    }

    @Test
    void doesNotRetryWithoutSqlCause() throws Exception {
        assertThat(predicate.shouldRetry(method(), new PessimisticLockingFailureException("x"))).isFalse();
    }

    @Test
    void doesNotRetryWhenSqlExceptionIsNotTheMostSpecificCause() throws Exception {
        PessimisticLockingFailureException failure = new PessimisticLockingFailureException("x",
                new SQLException("x", "40001", new IllegalStateException("root")));

        assertThat(predicate.shouldRetry(method(), failure)).isFalse();
    }

    @Test
    void retriesWhenSqlExceptionIsNestedDeeper() throws Exception {
        PessimisticLockingFailureException failure = new PessimisticLockingFailureException("x",
                new RuntimeException("wrapper", new SQLException("x", "40001")));

        assertThat(predicate.shouldRetry(method(), failure)).isTrue();
    }
}
