package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.kgtech.inventoryapi.inventory.Invariants;

/**
 * Invariant 3, "Recorded": after every test that uses the Spring context and Postgres, each sku.quantity equals the
 * ledger SUM. Attached to {@link IntegrationTest}, so no test class opts in. A test that breaks the rule on purpose
 * says so with {@link AllowsBalanceMismatch}. Tests without a context (unit tests) never reach this callback.
 * <p>
 * Limits: this runs after the test's own {@code @AfterEach} methods, so a class that truncates the tables there leaves
 * nothing to check and the test passes vacuously; clean in {@code @BeforeEach} (as {@link Tables#reset} users do).
 * It cannot tell "no writes" from "emptied", so it does not fail on empty tables.
 */
public class BalancesRecordedExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        if (optedOut(context)) {
            return;
        }
        ApplicationContext applicationContext;
        try {
            applicationContext = SpringExtension.getApplicationContext(context);
        } catch (IllegalStateException e) {
            return; // no context could be built: the test itself already failed for that reason
        }
        JdbcClient jdbc = applicationContext.getBeanProvider(JdbcClient.class).getIfAvailable();
        if (jdbc == null) {
            return;
        }
        assertThat(Invariants.balanceMismatches(jdbc))
                .as("invariant 3: sku.quantity must equal the ledger SUM after the test (SKUs that differ)")
                .isEmpty();
    }

    private static boolean optedOut(ExtensionContext context) {
        return context.getTestMethod().map(m -> AnnotationSupport.isAnnotated(m, AllowsBalanceMismatch.class))
                .orElse(false)
                || context.getTestClass().map(c -> AnnotationSupport.isAnnotated(c, AllowsBalanceMismatch.class))
                        .orElse(false);
    }
}
