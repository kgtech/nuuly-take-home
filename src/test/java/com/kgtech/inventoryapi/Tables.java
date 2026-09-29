package com.kgtech.inventoryapi;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The one test cleanup (#24): TRUNCATE, test-only; the application never deletes key, ledger or sku rows (G5, R9). */
public final class Tables {

    private static final String TRUNCATE = "TRUNCATE inventory_ledger, sku, idempotency_keys RESTART IDENTITY CASCADE";

    private Tables() {
    }

    public static void reset(JdbcClient jdbc) {
        jdbc.sql(TRUNCATE).update();
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update(TRUNCATE);
    }
}
