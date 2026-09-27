package com.kgtech.inventoryapi.inventory;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Queries for the invariants of DESIGN-V2 §1, shared by the concurrency and fault tests. */
final class Invariants {

    private Invariants() {
    }

    /** SKUs whose balance row differs from SUM(quantity_delta); empty when "recorded" holds. */
    static List<String> balanceMismatches(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT s.sku_id FROM sku s
                WHERE s.quantity <> (SELECT COALESCE(SUM(l.quantity_delta), 0) FROM inventory_ledger l
                                     WHERE l.sku_id = s.sku_id)
                """).query(String.class).list();
    }

    /** The smallest balance; never negative when "no oversell" holds. */
    static long minQuantity(JdbcClient jdbc) {
        return jdbc.sql("SELECT COALESCE(MIN(quantity), 0) FROM sku").query(Long.class).single();
    }
}
