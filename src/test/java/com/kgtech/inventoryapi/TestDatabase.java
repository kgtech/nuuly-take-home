package com.kgtech.inventoryapi;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only cleanup for Testcontainers classes that commit rows (S11, C4). The V3 triggers reject UPDATE, DELETE and
 * TRUNCATE on sku and inventory_ledger, so this is a test-only bypass: one new transaction sets
 * {@code session_replication_role = replica} (which stops the triggers from firing) and a lock timeout, both with
 * SET LOCAL so they end with the transaction, then truncates. Production code never sets the replica role, and the
 * application never deletes key, ledger or sku rows (G5, R9).
 */
public final class TestDatabase {

    public static final String BYPASS_TRIGGERS = "SET LOCAL session_replication_role = replica";
    public static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '5s'";
    public static final String TRUNCATE_ALL =
            "TRUNCATE sku, inventory_ledger, idempotency_keys RESTART IDENTITY CASCADE";

    private TestDatabase() {}

    public static void truncateAll(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            jdbc.sql(BYPASS_TRIGGERS).update();
            jdbc.sql(LOCK_TIMEOUT).update();
            jdbc.sql(TRUNCATE_ALL).update();
        });
    }
}
