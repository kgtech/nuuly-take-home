package com.kgtech.inventoryapi.idempotency;

import java.lang.reflect.Method;

import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Matches {@link Idempotent} methods; throws IllegalStateException at proxy creation unless the parameters are
 * (String skuId, int quantity | Fingerprinted request, String idempotencyKey) (Z1, A29).
 */
final class IdempotentMethodPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        if (!AnnotatedElementUtils.hasAnnotation(method, Idempotent.class)) {
            return false;
        }
        Class<?>[] p = method.getParameterTypes();
        boolean shape = p.length == 3 && p[0] == String.class && p[2] == String.class
                && (p[1] == int.class || Fingerprinted.class.isAssignableFrom(p[1]));
        if (!shape) {
            throw new IllegalStateException("@Idempotent method " + method
                    + " must take (String skuId, int quantity | Fingerprinted request, String idempotencyKey)");
        }
        return true;
    }
}
