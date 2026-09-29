package com.kgtech.inventoryapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a test method or class out of the "Recorded" check that {@link BalancesRecordedExtension} runs after every
 * {@code @IntegrationTest} test. Use it only where the test deliberately leaves {@code sku.quantity} different from the
 * ledger SUM, and say why: the reason is mandatory.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowsBalanceMismatch {

    String reason();
}
