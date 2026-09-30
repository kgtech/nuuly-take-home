package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * S11, D9, E1: concurrent stock writes to one SKU through the service; the HTTP versions are
 * InventoryHttpConcurrencyTest's. Not @Transactional: every thread commits its own READ COMMITTED transaction and
 * waits on the sku row's lock. Tables are reset before each test, and every test ends with the invariants (A14).
 */
@IntegrationTest
class InventoryConcurrencyTest {

    /** At most 8 threads per SKU (W2, S11). */
    private static final int THREADS = 8;

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        Tables.reset(jdbc);
    }

    private static String newSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long quantity(String sku) {
        return jdbc.sql("SELECT quantity FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    private void assertInvariants() {
        assertThat(Invariants.balanceMismatches(jdbc)).as("quantity = SUM(quantity_delta)").isEmpty();
        assertThat(Invariants.minQuantity(jdbc)).isNotNegative();
    }

    /** D9: 8 purchases of 1 against stock 5; exactly 5 succeed, the rest are Insufficient, and nothing oversells. */
    @Test
    void concurrentPurchasesNeverOversell() throws InterruptedException {
        int stock = 5;
        String sku = newSku("race-buy");
        Tables.seed(jdbc, sku, stock);

        List<StockOutcome.Purchase> outcomes = Concurrently.run(THREADS, () -> switch (service.purchase(sku, 1, null)) {
            case WriteResult.Done<StockOutcome.Purchase>(StockOutcome.Purchase outcome) -> outcome;
            case WriteResult<StockOutcome.Purchase> other -> throw new AssertionError("not an outcome: " + other);
        });

        assertThat(outcomes).filteredOn(StockOutcome.Ok.class::isInstance).hasSize(stock);
        assertThat(outcomes).filteredOn(StockOutcome.Insufficient.class::isInstance).hasSize(THREADS - stock);
        assertThat(outcomes).filteredOn(StockOutcome.Ok.class::isInstance)
                .extracting(o -> ((StockOutcome.Ok) o).quantity())
                .containsExactlyInAnyOrder(4L, 3L, 2L, 1L, 0L);
        assertThat(quantity(sku)).isZero();
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(stock);
        assertInvariants();
    }

    /** S11: 8 adds of 1 to one new SKU all return Ok; none is lost and the SKU has one row. */
    @Test
    void concurrentAddsAreNeverLost() throws InterruptedException {
        String sku = newSku("race-add");

        List<StockOutcome.Add> outcomes = Concurrently.run(THREADS, () -> switch (service.add(sku, 1, null)) {
            case WriteResult.Done<StockOutcome.Add>(StockOutcome.Add outcome) -> outcome;
            case WriteResult<StockOutcome.Add> other -> throw new AssertionError("not an outcome: " + other);
        });

        assertThat(outcomes).hasSize(THREADS).allMatch(StockOutcome.Ok.class::isInstance);
        assertThat(outcomes).extracting(o -> ((StockOutcome.Ok) o).quantity())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(quantity(sku)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(THREADS);
        assertInvariants();
    }
}
