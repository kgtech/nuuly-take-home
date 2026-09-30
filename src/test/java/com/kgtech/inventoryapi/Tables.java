package com.kgtech.inventoryapi;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one test cleanup and seed (#24, E3, S11). reset is TRUNCATE, test-only (the application never deletes key, ledger
 * or sku rows, G5, R9), which the append-only triggers don't block. seed needs V3's sku.quantity and sku.version.
 */
public final class Tables {

    private static final String TRUNCATE = "TRUNCATE inventory_ledger, sku, idempotency_keys RESTART IDENTITY CASCADE";

    private static final String SEED_SKU = """
            INSERT INTO sku (sku_id, quantity, version) VALUES (?, ?, 1)
            ON CONFLICT (sku_id) DO UPDATE SET quantity = sku.quantity + EXCLUDED.quantity, version = sku.version + 1
            """;
    private static final String SEED_LEDGER =
            "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, 'add')";

    private Tables() {
    }

    public static void reset(JdbcClient jdbc) {
        jdbc.sql(TRUNCATE).update();
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.update(TRUNCATE);
    }

    /** Seeds stock the way a committed add leaves it: the balance row plus one ledger row (E1, A14). */
    public static void seed(JdbcClient jdbc, String skuId, long quantity) {
        jdbc.sql(SEED_SKU).params(skuId, quantity).update();
        jdbc.sql(SEED_LEDGER).params(skuId, quantity).update();
    }

    public static void seed(JdbcTemplate jdbc, String skuId, long quantity) {
        jdbc.update(SEED_SKU, skuId, quantity);
        jdbc.update(SEED_LEDGER, skuId, quantity);
    }
}
