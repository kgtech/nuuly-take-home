package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/** D3 reads (SUM(quantity_delta)::bigint) and AC9 COLLATE "C" order, against Postgres (S11). */
@IntegrationTest
class SkuRepositoryTest {

    @Autowired
    SkuRepository repository;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        Tables.reset(jdbc);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void seedSku(String sku) {
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(sku).update();
    }

    private void seedLedger(String sku, long delta) {
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, ?)")
                .params(sku, delta, delta > 0 ? "add" : "purchase")
                .update();
    }

    @Test
    void findQuantityReturnsLedgerSum() {
        String sku = "sum-" + suffix();
        seedSku(sku);
        seedLedger(sku, Long.MAX_VALUE - 5);
        seedLedger(sku, 5);
        seedLedger(sku, -7);

        assertThat(repository.findQuantity(sku)).contains(Long.MAX_VALUE - 7);
    }

    @Test
    void findQuantityIsZeroForSkuWithoutLedgerRows() {
        String sku = "bare-" + suffix();
        seedSku(sku);

        assertThat(repository.findQuantity(sku)).contains(0L);
    }

    @Test
    void findQuantityEmptyForMissingSku() {
        String sku = "missing-" + suffix();

        assertThat(repository.findQuantity(sku)).isEmpty();
        assertThat(repository.findQuantity(sku.toUpperCase())).isEmpty();
    }

    /**
     * G9, D3: one page is the next {@code limit} SKUs strictly after the cursor in COLLATE "C" order, with ::bigint
     * ledger sums (0 for a SKU without ledger rows). The unique prefix keeps other tests' rows out of the window.
     */
    @Test
    void findQuantitiesAfterPagesInCCollationOrder() {
        String prefix = "q" + suffix() + "-";
        String upperA = prefix + "A";
        String upperB = prefix + "B";
        String upperZ = prefix + "Z";
        String lowerA = prefix + "a";
        String lowerB = prefix + "b";
        for (String sku : List.of(lowerB, upperZ, upperA, lowerA, upperB)) {
            seedSku(sku);
        }
        seedLedger(upperA, 3);
        seedLedger(upperB, 10);
        seedLedger(upperB, -4);
        seedLedger(upperZ, Long.MAX_VALUE);
        seedLedger(lowerB, 2);

        assertThat(repository.findQuantitiesAfter(prefix, 3))
                .extracting(SkuQuantity::getSkuId, SkuQuantity::getQuantity)
                .containsExactly(tuple(upperA, 3L), tuple(upperB, 6L), tuple(upperZ, Long.MAX_VALUE));
        assertThat(repository.findQuantitiesAfter(upperA, 2)).extracting(SkuQuantity::getSkuId)
                .containsExactly(upperB, upperZ);
        assertThat(repository.findQuantitiesAfter(upperZ, 2))
                .extracting(SkuQuantity::getSkuId, SkuQuantity::getQuantity)
                .containsExactly(tuple(lowerA, 0L), tuple(lowerB, 2L));
    }

    @Test
    void findQuantitiesAfterBeyondLastIsEmpty() {
        assertThat(repository.findQuantitiesAfter("\u007f", 5)).isEmpty();
    }
}
