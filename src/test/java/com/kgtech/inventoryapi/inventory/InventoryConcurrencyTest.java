package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * S11, D9, W2 (owner decision OQ2): concurrent stock writes through the service; the HTTP versions come in #4.
 * Not @Transactional: every thread commits its own SERIALIZABLE transaction. Tables are emptied before each test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryConcurrencyTest {

    /** W2 caps concurrency tests at 8 threads per SKU. */
    private static final int THREADS = 8;

    @Autowired
    InventoryService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // test-only deletes; the application never deletes ledger or sku rows (G5)
        jdbc.update("DELETE FROM inventory_ledger");
        jdbc.update("DELETE FROM sku");
    }

    private static String newSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Runs {@code call} on {@link #THREADS} threads released together; any thrown exception fails the test. */
    private <T> List<T> runTogether(Supplier<T> call) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                Callable<T> task = () -> {
                    ready.countDown();
                    start.await();
                    return call.get();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }
        List<T> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        assertThat(failures).as("exceptions thrown by writer threads (retries must not run out)").isEmpty();
        return results;
    }

    private long balance(String sku) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?", Long.class,
                sku);
        return sum == null ? 0 : sum;
    }

    private long count(String sql, String sku) {
        Long n = jdbc.queryForObject(sql, Long.class, sku);
        return n == null ? 0 : n;
    }

    @Test
    void concurrentPurchasesNeverOversell() throws InterruptedException {
        int stock = 5;
        String sku = newSku("race-buy");
        jdbc.update("INSERT INTO sku (sku_id) VALUES (?)", sku);
        jdbc.update("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, 'add')", sku, stock);

        List<StockOutcome.Purchase> outcomes = runTogether(() -> service.purchase(sku, 1));

        assertThat(outcomes).filteredOn(StockOutcome.Ok.class::isInstance).hasSize(stock);
        assertThat(outcomes).filteredOn(StockOutcome.Insufficient.class::isInstance).hasSize(THREADS - stock);
        assertThat(outcomes).filteredOn(StockOutcome.Ok.class::isInstance)
                .extracting(o -> ((StockOutcome.Ok) o).quantity())
                .containsExactlyInAnyOrder(4L, 3L, 2L, 1L, 0L);
        assertThat(balance(sku)).isZero();
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(stock);
    }

    @Test
    void concurrentAddsAreNeverLost() throws InterruptedException {
        String sku = newSku("race-add");

        List<StockOutcome.Add> outcomes = runTogether(() -> service.add(sku, 1));

        assertThat(outcomes).hasSize(THREADS).allMatch(StockOutcome.Ok.class::isInstance);
        assertThat(outcomes).extracting(o -> ((StockOutcome.Ok) o).quantity())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(balance(sku)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(THREADS);
    }
}
