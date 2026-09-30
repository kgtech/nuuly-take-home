package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * Purchases racing purchases of different sizes, and purchases racing restocks (the API's only ways to change a
 * balance: add is positive, purchase is the one decrement, so a "negative adjustment" is a purchase). Every test
 * ends by asserting the invariants of A14: the balance equals the ledger sum and never drops below 0.
 * Not @Transactional: each service call commits its own READ COMMITTED transaction; at most 8 threads per SKU (W2).
 */
@IntegrationTest
class MixedStockConcurrencyTest {

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

    private long quantity(String sku) {
        return jdbc.sql("SELECT quantity FROM sku WHERE sku_id = :id").param("id", sku).query(Long.class).single();
    }

    private void assertInvariants() {
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
        assertThat(Invariants.minQuantity(jdbc)).isNotNegative();
    }

    /** Purchases of different sizes on 20 units (24 requested): never oversold, and nothing is left that a refused purchase could have taken. */
    @Test
    void mixedSizePurchasesNeverOversellAndLeaveNoFittableStock() throws InterruptedException {
        int[] sizes = {7, 5, 3, 3, 2, 2, 1, 1};
        for (int round = 0; round < ROUNDS; round++) {
            Tables.reset(jdbc);
            String sku = "mixed-buy-" + round;
            Tables.seed(jdbc, sku, 20);
            AtomicInteger next = new AtomicInteger();

            List<int[]> results = Concurrently.run(THREADS, () -> {
                int size = sizes[next.getAndIncrement()];
                WriteResult r = service.purchase(sku, size);
                assertThat(r).isInstanceOfAny(StockOutcome.Ok.class, StockOutcome.Insufficient.class);
                return new int[] {size, r instanceof StockOutcome.Ok ? 1 : 0};
            });

            int sold = results.stream().filter(r -> r[1] == 1).mapToInt(r -> r[0]).sum();
            int smallestRefused = results.stream().filter(r -> r[1] == 0).mapToInt(r -> r[0]).min().orElse(Integer.MAX_VALUE);
            assertThat(sold).isLessThanOrEqualTo(20);
            assertThat(quantity(sku)).isEqualTo(20 - sold);
            // the balance only falls in this test, so a purchase refused at any moment is still too big at the end
            assertThat(quantity(sku)).isLessThan(smallestRefused);
            assertInvariants();
        }
    }

    /** Small purchases on a SKU that is being restocked: every unit sold was on hand at that moment. */
    @Test
    void purchasesRacingRestocksLoseNothingAndOversellNothing() throws InterruptedException {
        int initial = 5, restock = 2, restocksPerThread = 10, buyersPerSide = THREADS / 2, buysPerThread = 20;
        String sku = "buy-vs-restock";
        Tables.seed(jdbc, sku, initial);
        AtomicInteger role = new AtomicInteger();

        List<int[]> results = Concurrently.run(THREADS, () -> {
            boolean buyer = role.getAndIncrement() < buyersPerSide;
            int ok = 0, refused = 0;
            for (int i = 0; i < (buyer ? buysPerThread : restocksPerThread); i++) {
                WriteResult r = buyer ? service.purchase(sku, 1) : service.add(sku, restock);
                if (r instanceof StockOutcome.Ok) {
                    ok++;
                } else {
                    refused++;
                    assertThat(buyer).as("only a purchase can be refused here").isTrue();
                    assertThat(r).isInstanceOf(StockOutcome.Insufficient.class);
                }
            }
            return new int[] {buyer ? 1 : 0, ok, refused};
        });

        int sold = results.stream().filter(r -> r[0] == 1).mapToInt(r -> r[1]).sum();
        int restocks = results.stream().filter(r -> r[0] == 0).mapToInt(r -> r[1]).sum();
        assertThat(restocks).as("no restock refused or lost").isEqualTo((THREADS - buyersPerSide) * restocksPerThread);
        assertThat(quantity(sku)).isEqualTo(initial + (long) restocks * restock - sold);
        assertThat(sold).isLessThanOrEqualTo(initial + restocks * restock);
        assertInvariants();
    }

    /** Whole-stock purchases against restocks, repeated to hit the interleavings: a purchase succeeds only if a full restock was visible. */
    @Test
    void wholeStockPurchasesRacingRestocksNeverGoNegative() throws InterruptedException {
        for (int round = 0; round < ROUNDS; round++) {
            Tables.reset(jdbc);
            String sku = "whole-vs-restock-" + round;
            Tables.seed(jdbc, sku, 10);
            AtomicInteger role = new AtomicInteger();

            List<int[]> results = Concurrently.run(THREADS, () -> {
                boolean buyer = role.getAndIncrement() % 2 == 0;
                WriteResult r = buyer ? service.purchase(sku, 10) : service.add(sku, 10);
                return new int[] {buyer ? 1 : 0, r instanceof StockOutcome.Ok ? 1 : 0};
            });

            long sold = results.stream().filter(r -> r[0] == 1 && r[1] == 1).count();
            long restocks = results.stream().filter(r -> r[0] == 0 && r[1] == 1).count();
            assertThat(restocks).isEqualTo(THREADS / 2);
            // 10 on hand plus 10 per restock, 10 per purchase; the quantity is a multiple of 10 and never below 0
            assertThat(quantity(sku)).isEqualTo(10 + 10 * restocks - 10 * sold);
            assertInvariants();
        }
    }

    /** Purchases against an empty, seeded SKU while one thread restocks it: sold units never exceed units added. */
    @Test
    void purchasesAgainstEmptyStockOnlySellWhatWasAdded() throws InterruptedException {
        List<String> skus = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            String sku = "empty-" + round;
            skus.add(sku);
            Tables.seed(jdbc, sku, 0);
            AtomicInteger role = new AtomicInteger();

            List<Integer> sold = Concurrently.run(THREADS, () -> {
                if (role.getAndIncrement() == 0) {
                    assertThat(service.add(sku, 1)).isInstanceOf(StockOutcome.Ok.class);
                    return 0;
                }
                return service.purchase(sku, 1) instanceof StockOutcome.Ok ? 1 : 0;
            });

            int total = sold.stream().mapToInt(Integer::intValue).sum();
            assertThat(total).isLessThanOrEqualTo(1);
            assertThat(quantity(sku)).isEqualTo(1 - total);
        }
        assertInvariants();
    }
}
