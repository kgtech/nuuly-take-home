package com.kgtech.inventoryapi.inventory;

import java.lang.reflect.Method;

import org.springframework.resilience.retry.MethodRetryPredicate;

/** Retries only when the root SQLState is 40001 or 40P01 (Y2). */
final class SerializationFailure implements MethodRetryPredicate {

    @Override
    public boolean shouldRetry(Method method, Throwable failure) {
        throw new UnsupportedOperationException("not implemented");
    }
}
