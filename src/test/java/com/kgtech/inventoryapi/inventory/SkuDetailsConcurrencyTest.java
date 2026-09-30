package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * DESIGN-V2 §8 "Edit": a details PUT never waits on a stock write. The concurrent creates and conditional PUTs over
 * HTTP are SkuDetailsPutConcurrencyTest's; the /v2 add and purchase races are V2IdempotencyConcurrencyTest's.
 */
@IntegrationTest
class SkuDetailsConcurrencyTest {

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

    /**
     * OD-6, DESIGN-V2 §8 "Edit": a PUT .../details completes while a purchase holds the sku row lock, on both paths:
     * the first insert of a details row (a SKU the spec's add created; the FK takes a KEY SHARE on the locked row,
     * compatible with the stock write's FOR NO KEY UPDATE) and the update of an existing one (no sku lock at all).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "details row exists: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aPutCompletesWhileAPurchaseHoldsTheRowLock(boolean detailsExist) throws Exception {
        Tables.seed(jdbc, "locked-1", 5);
        if (detailsExist) {
            assertThat(service.putDetails("locked-1", DETAILS, new DetailsPrecondition.Any()))
                    .isInstanceOf(PutResult.Replaced.class);
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
            PutResult result = service.putDetails("locked-1",
                    new SkuDetails("Renamed", "", Optional.empty(), List.of()),
                    new DetailsPrecondition.Versions(List.of(expectedVersion)));
            assertThat(result).isInstanceOf(PutResult.Replaced.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
        } finally {
            release.countDown();
            writer.join(30_000);
        }
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", "locked-1"))
                .isEqualTo(expectedVersion + 1);
    }
}
