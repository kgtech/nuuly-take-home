package com.kgtech.inventoryapi;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test-only cleanup for Testcontainers classes that commit rows (S11, C4). TRUNCATE, not DELETE: the V3 triggers
 * reject DELETE on sku and inventory_ledger, and TRUNCATE fires no row or UPDATE/DELETE trigger. The application
 * never deletes key, ledger or sku rows (G5, R9).
 */
public final class TestDatabase {

    public static final String TRUNCATE_ALL =
            "TRUNCATE sku, inventory_ledger, idempotency_keys RESTART IDENTITY CASCADE";

    private TestDatabase() {}

    public static void truncateAll(JdbcClient jdbc) {
        jdbc.sql(TRUNCATE_ALL).update();
    }
}
