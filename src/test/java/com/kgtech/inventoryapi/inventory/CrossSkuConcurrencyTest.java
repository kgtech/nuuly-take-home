package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Writers on distinct SKUs never interfere (C-03, issue #57): 8 threads each hammer their own SKU with adds and
 * purchases; every call succeeds, and afterwards each balance equals its ledger sum and none is negative.
 * Not @Transactional: each service call commits its own transaction.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrossSkuConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 25;

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    @Test
    void distinctSkusWriteConcurrentlyWithoutErrors() throws InterruptedException {
        AtomicInteger next = new AtomicInteger();
        List<Integer> failures = Concurrently.run(THREADS, () -> {
            String sku = "sku-" + next.getAndIncrement();
            int notOk = 0;
            for (int i = 0; i < ROUNDS; i++) {
                if (!(service.add(sku, 3, null) instanceof StockOutcome.Ok)) {
                    notOk++;
                }
                if (!(service.purchase(sku, 2, null) instanceof StockOutcome.Ok)) {
                    notOk++;
                }
            }
            return notOk;
        });

        assertThat(failures).containsOnly(0);
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isEqualTo(THREADS);
        assertThat(jdbc.sql("SELECT DISTINCT quantity FROM sku").query(Long.class).list()).containsExactly((long) ROUNDS);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
        assertThat(Invariants.minQuantity(jdbc)).isNotNegative();
    }
}
