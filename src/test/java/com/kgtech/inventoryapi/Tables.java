package com.kgtech.inventoryapi;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The one test cleanup (C-19): TRUNCATE, which the append-only triggers don't block. */
public final class Tables {

    private static final String TRUNCATE = "TRUNCATE inventory_ledger, sku_details, sku, idempotency_keys RESTART IDENTITY CASCADE";

    private Tables() {
    }

    public static void reset(JdbcClient jdbc) {
        jdbc.sql(TRUNCATE).update();
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update(TRUNCATE);
    }

    /**
     * Row counts of every table a write can touch, so a test can assert that a rejected request wrote nothing: no sku,
     * sku_details, inventory_ledger or idempotency_keys row.
     */
    public static Map<String, Long> counts(JdbcClient jdbc) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {"sku", "sku_details", "inventory_ledger", "idempotency_keys"}) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    /** Seeds stock the way a committed add would leave it: the balance row, whose V5 trigger writes the ledger row (A14). */
    public static void seed(JdbcClient jdbc, String skuId, long quantity) {
        jdbc.sql(SEED_SKU).params(skuId, quantity).update();
    }

    public static void seed(JdbcTemplate jdbc, String skuId, long quantity) {
        jdbc.update(SEED_SKU, skuId, quantity);
    }

    private static final String SEED_SKU = """
            INSERT INTO sku (sku_id, quantity, version) VALUES (?, ?, 1)
            ON CONFLICT (sku_id) DO UPDATE SET quantity = sku.quantity + EXCLUDED.quantity, version = sku.version + 1
            """;
}
