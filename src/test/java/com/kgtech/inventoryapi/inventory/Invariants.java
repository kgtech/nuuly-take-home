package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Queries for the balance-row invariants (E1, A14): sku.quantity equals SUM(quantity_delta) per SKU and never goes
 * below 0. Shared by the concurrency tests and the V3 backfill test. Each query first asserts that V3's columns exist,
 * so a database without them fails on an assertion rather than an SQL error.
 */
final class Invariants {

    private Invariants() {
    }

    /** SKUs whose balance row differs from SUM(quantity_delta); empty when the invariant holds. */
    static List<String> balanceMismatches(JdbcClient jdbc) {
        assertBalanceColumns(jdbc);
        return jdbc.sql("""
                SELECT s.sku_id FROM sku s
                WHERE s.quantity <> (SELECT COALESCE(SUM(l.quantity_delta), 0) FROM inventory_ledger l
                                     WHERE l.sku_id = s.sku_id)
                """).query(String.class).list();
    }

    /** The smallest balance; never negative when "no oversell" holds. */
    static long minQuantity(JdbcClient jdbc) {
        assertBalanceColumns(jdbc);
        return jdbc.sql("SELECT COALESCE(MIN(quantity), 0) FROM sku").query(Long.class).single();
    }

    /** V3 (E3): sku.quantity and sku.version exist. Call it before any statement that reads them. */
    static void assertBalanceColumns(JdbcClient jdbc) {
        List<String> columns = jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'sku'
                  AND column_name IN ('quantity', 'version')
                """).query(String.class).list();
        assertThat(columns).as("sku balance columns (V3)").containsExactlyInAnyOrder("quantity", "version");
    }
}
