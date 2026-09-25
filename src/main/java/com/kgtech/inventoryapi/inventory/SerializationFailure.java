package com.kgtech.inventoryapi.inventory;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Set;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.resilience.retry.MethodRetryPredicate;

/** Retries only when the root SQLState is 40001 or 40P01 (Y2). */
final class SerializationFailure implements MethodRetryPredicate {

    private static final Set<String> RETRYABLE = Set.of("40001", "40P01");

    @Override
    public boolean shouldRetry(Method method, Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure) instanceof SQLException sql
                && sql.getSQLState() != null
                && RETRYABLE.contains(sql.getSQLState());
    }
}
