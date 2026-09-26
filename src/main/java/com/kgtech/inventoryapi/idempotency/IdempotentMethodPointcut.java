package com.kgtech.inventoryapi.idempotency;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Matches {@link Idempotent} methods; throws IllegalStateException at proxy creation unless the parameters are
 * (String, int, String) (Z1).
 */
final class IdempotentMethodPointcut extends StaticMethodMatcherPointcut {

    private static final Class<?>[] PARAMETERS = {String.class, int.class, String.class};

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        if (!AnnotatedElementUtils.hasAnnotation(method, Idempotent.class)) {
            return false;
        }
        if (!Arrays.equals(method.getParameterTypes(), PARAMETERS)) {
            throw new IllegalStateException("@Idempotent method " + method
                    + " must take (String skuId, int quantity, String idempotencyKey)");
        }
        return true;
    }
}
