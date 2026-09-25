package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * AC5 / W2: the balance SUM and both write statements read inventory_ledger through inventory_ledger_sku.
 * {@code @Transactional} is allowed here only because this class never calls the service: seed rows, ANALYZE and
 * EXPLAIN all run in the test's own transaction, which rolls back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LedgerQueryPlanTest {

    private static final int SKUS = 500;
    private static final int ROWS_PER_SKU = 10;

    @Autowired
    JdbcTemplate jdbc;

    private String prefix;
    private String seeded;

    @BeforeEach
    void seed() {
        prefix = "plan-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        seeded = prefix + (SKUS / 2);
        jdbc.update("INSERT INTO sku (sku_id) SELECT ? || g FROM generate_series(1, ?) g", prefix, SKUS);
        jdbc.update("""
                INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
                SELECT ? || g, 1, 'add' FROM generate_series(1, ?) g, generate_series(1, ?) r
                """, prefix, SKUS, ROWS_PER_SKU);
        jdbc.execute("ANALYZE inventory_ledger");
    }

    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }

    @Test
    void sumQueryUsesSkuIndex() {
        String plan = explain(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = '" + seeded + "'");

        assertThat(plan).as(plan).contains("inventory_ledger_sku");
        assertThat(plan).as(plan).doesNotContain("Seq Scan on inventory_ledger");
    }

    @Test
    void addAndPurchaseStatementsUseSkuIndex() {
        String add = explain("""
                INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
                SELECT '%1$s', 3, 'add'
                WHERE (SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = '%1$s')
                      <= 9223372036854775807 - 3
                RETURNING ((SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = '%1$s') + 3)::bigint
                """.formatted(seeded));
        String purchase = explain("""
                INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
                SELECT '%1$s', -3, 'purchase'
                WHERE EXISTS (SELECT 1 FROM sku WHERE sku_id = '%1$s')
                  AND (SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = '%1$s') >= 3
                RETURNING ((SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = '%1$s') - 3)::bigint
                """.formatted(seeded));

        for (String plan : new String[] {add, purchase}) {
            assertThat(plan).as(plan).contains("inventory_ledger_sku");
            assertThat(plan).as(plan).doesNotContain("Seq Scan on inventory_ledger");
        }
    }
}
