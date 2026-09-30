package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/** Issue #71 AC3 (DESIGN-V2 §8 "Create"): concurrent creates of one id, and a create racing the spec's add. */
@IntegrationTest
class SkuDetailsConcurrencyTest {

    private static final int THREADS = 8;
    private static final SkuDetails DETAILS = new SkuDetails("Linen shirt", "", Optional.empty(), List.of());

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    javax.sql.DataSource dataSource;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    @Test
    void concurrentCreatesOfOneIdGiveOneCreatedAndTheRest409() throws InterruptedException {
        String sku = "race-" + UUID.randomUUID().toString().substring(0, 8);

        List<DetailsOutcome> results = Concurrently.run(THREADS, () ->
                ((WriteResult.Done<DetailsOutcome>) service.create(sku, new CreateSku(DETAILS, 3), null)).outcome());

        assertThat(results).filteredOn(DetailsOutcome.Created.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(DetailsOutcome.AlreadyExists.class::isInstance).hasSize(THREADS - 1);
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(3);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void aCreateAndTheSpecAddOnOneNewIdBothLand() throws InterruptedException {
        String sku = "race-add-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger turn = new AtomicInteger();

        List<Object> results = Concurrently.run(2, () -> turn.getAndIncrement() == 0
                ? ((WriteResult.Done<DetailsOutcome>) service.create(sku, new CreateSku(DETAILS, 5), null)).outcome()
                : ((WriteResult.Done<StockOutcome.Add>) service.add(sku, 2, null)).outcome());

        Object created = results.stream().filter(DetailsOutcome.class::isInstance).findFirst().orElseThrow();
        Object added = results.stream().filter(StockOutcome.class::isInstance).findFirst().orElseThrow();
        assertThat(added).isInstanceOf(StockOutcome.Ok.class);
        // Either order: the add lands on the created SKU (details kept) or the create finds the SKU and answers 409.
        if (created instanceof DetailsOutcome.Created) {
            assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(7);
            assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        } else {
            assertThat(created).isInstanceOf(DetailsOutcome.AlreadyExists.class);
            assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isZero();
        }
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    /** #71 AC4, S11 for the create shape: eight concurrent keyed creates with one fresh key change stock once. */
    @Test
    void concurrentKeyedCreatesWithOneFreshKeyProduceOneCreate() throws InterruptedException {
        String sku = "keyed-" + UUID.randomUUID().toString().substring(0, 8);
        String key = UUID.randomUUID().toString();

        List<WriteResult<DetailsOutcome>> results = Concurrently.run(THREADS,
                () -> service.create(sku, new CreateSku(DETAILS, 3), key));

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(WriteResult.Stored.class));
        java.util.Set<String> bodies = new java.util.HashSet<>();
        for (WriteResult<DetailsOutcome> r : results) {
            com.kgtech.inventoryapi.idempotency.StoredResponse response =
                    ((WriteResult.Stored<DetailsOutcome>) r).response();
            assertThat(response.status()).isEqualTo(201);
            bodies.add(response.body());
        }
        assertThat(bodies).as("every reply is byte-identical").hasSize(1);
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single()).isEqualTo(1);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();

        // The 409 variant: the same fresh key on a SKU that now exists stores and replays one 409.
        String again = UUID.randomUUID().toString();
        List<WriteResult<DetailsOutcome>> conflicts = Concurrently.run(THREADS,
                () -> service.create(sku, new CreateSku(DETAILS, 3), again));
        assertThat(conflicts).allSatisfy(
                r -> assertThat(((WriteResult.Stored<DetailsOutcome>) r).response().status()).isEqualTo(409));
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
    }

    /**
     * DESIGN-V2 §8 "Edit": a PUT completes while a purchase holds the sku row lock, on both paths: the first insert
     * of a details row (a v1-created SKU; the FK takes a KEY SHARE on the locked row, compatible with the stock write's
     * FOR NO KEY UPDATE) and the update of an existing one (no sku lock at all).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "details row exists: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aPutCompletesWhileAPurchaseHoldsTheRowLock(boolean detailsExist) throws Exception {
        if (detailsExist) {
            assertThat(((WriteResult.Done<DetailsOutcome>) service.create("locked-1", new CreateSku(DETAILS, 5), null))
                    .outcome()).isInstanceOf(DetailsOutcome.Created.class);
        } else {
            Tables.seed(jdbc, "locked-1", 5);
        }
        long expectedVersion = detailsExist ? 1L : 0L;
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try (java.sql.Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE sku SET quantity = quantity - 1, version = version + 1 "
                            + "WHERE sku_id = 'locked-1'");
                }
                locked.countDown();
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                connection.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writer.start();
        assertThat(locked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            ReplaceResult result = service.replaceDetails("locked-1",
                    new SkuDetails("Renamed", "", Optional.empty(), List.of()),
                    new DetailsPrecondition.Versions(List.of(expectedVersion)));
            assertThat(result).isInstanceOf(ReplaceResult.Replaced.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
        } finally {
            release.countDown();
            writer.join(30_000);
        }
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", "locked-1"))
                .isEqualTo(expectedVersion + 1);
    }

    @Test
    void concurrentConditionalPutsApplyExactlyOne() throws InterruptedException {
        assertThat(((WriteResult.Done<DetailsOutcome>) service.create("edit-1", new CreateSku(DETAILS, 1), null))
                .outcome()).isInstanceOf(DetailsOutcome.Created.class);
        DetailsPrecondition version1 = new DetailsPrecondition.Versions(List.of(1L));
        AtomicInteger n = new AtomicInteger();

        List<ReplaceResult> results = Concurrently.run(THREADS, () -> service.replaceDetails("edit-1",
                new SkuDetails("Edit " + n.getAndIncrement(), "", Optional.empty(), List.of()), version1));

        assertThat(results).filteredOn(ReplaceResult.Replaced.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ReplaceResult.VersionMismatch.class::isInstance).hasSize(THREADS - 1);
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", "edit-1")).isEqualTo(2);
    }
}
