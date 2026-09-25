package com.kgtech.inventoryapi.idempotency;

import java.lang.reflect.Method;

import org.springframework.aop.support.StaticMethodMatcherPointcut;

/**
 * Matches {@link Idempotent} methods; throws IllegalStateException at proxy creation unless the parameters are
 * (String, int, String) (Z1).
 */
final class IdempotentMethodPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return false; // not implemented
    }
}
