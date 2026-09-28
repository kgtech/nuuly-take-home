package com.kgtech.inventoryapi.idempotency;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a keyed write whose Idempotency-Key the {@link IdempotencyInterceptor} handles (Z1). The method must be
 * {@code R m(String skuId, int quantity, String idempotencyKey)} or {@code R m(String skuId, Fingerprinted request,
 * String idempotencyKey)} (A29), and exactly one {@link IdempotentResults}{@code <R>} bean must exist.
 */
@Target(METHOD)
@Retention(RUNTIME)
@Documented
public @interface Idempotent {

    Operation value();
}
