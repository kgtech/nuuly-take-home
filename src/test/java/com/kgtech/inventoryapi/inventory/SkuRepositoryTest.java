package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/** D3 reads (SUM(quantity_delta)::bigint) and AC9 COLLATE "C" order, against Postgres (S11). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkuRepositoryTest {

    @Autowired
    SkuRepository repository;

    @Autowired
    JdbcClient jdbc;

    private final List<String> skus = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String sku : skus) {
            jdbc.sql("DELETE FROM inventory_ledger WHERE sku_id = ?").param(sku).update();
            jdbc.sql("DELETE FROM sku WHERE sku_id = ?").param(sku).update();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void seedSku(String sku) {
        skus.add(sku);
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

    @Test
    void findAllQuantitiesOrdersByCCollation() {
        String s = suffix();
        String lowerB = "b" + s;
        String upperB = "B" + s;
        String lowerA = "a" + s;
        String upperZ = "Z" + s;
        seedSku(lowerB);
        seedSku(upperB);
        seedSku(lowerA);
        seedSku(upperZ);
        seedLedger(lowerB, 4);
        seedLedger(upperB, 10);
        seedLedger(upperB, -3);
        seedLedger(upperZ, Long.MAX_VALUE);

        List<SkuQuantity> mine = repository.findAllQuantities().stream()
                .filter(row -> row.getSkuId().endsWith(s))
                .toList();

        assertThat(mine).extracting(SkuQuantity::getSkuId, SkuQuantity::getQuantity).containsExactly(
                tuple(upperB, 7L),
                tuple(upperZ, Long.MAX_VALUE),
                tuple(lowerA, 0L),
                tuple(lowerB, 4L));
    }
}
