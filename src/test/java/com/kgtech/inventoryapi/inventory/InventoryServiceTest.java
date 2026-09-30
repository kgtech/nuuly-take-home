package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.IntegrationTest;

/** Stock writes and reads through the service against Postgres (S11, DESIGN-V2 §2), without an Idempotency-Key. */
@IntegrationTest
class InventoryServiceTest {

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

    private Map<String, Object> row(String sku) {
        return jdbc.sql("SELECT quantity, version FROM sku WHERE sku_id = ?").param(sku).query().singleRow();
    }

    private long ledgerRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    @Test
    void addCreatesTheSkuRowAndFirstLedgerRow() {
        assertThat(service.add("widget", 5)).isEqualTo(new StockOutcome.Ok(5));

        assertThat(row("widget")).containsEntry("quantity", 5L).containsEntry("version", 1L);
        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertThat(service.find("widget")).contains(new InventoryItem("widget", 5));
    }

    @Test
    void addAccumulatesAndBumpsTheVersion() {
        service.add("widget", 5);
        assertThat(service.add("widget", 7)).isEqualTo(new StockOutcome.Ok(12));

        assertThat(row("widget")).containsEntry("quantity", 12L).containsEntry("version", 2L);
        assertThat(ledgerRows("widget")).isEqualTo(2);
    }

    /** G12: up to 2^63-1 is accepted; one more is Overflow and writes nothing. */
    @Test
    void addRejectsOverflowWithoutWriting() {
        Tables.seed(jdbc, "big", Long.MAX_VALUE - 5);

        assertThat(service.add("big", 5)).isEqualTo(new StockOutcome.Ok(Long.MAX_VALUE));
        assertThat(service.add("big", 1)).isEqualTo(new StockOutcome.Overflow());

        assertThat(row("big")).containsEntry("quantity", Long.MAX_VALUE).containsEntry("version", 2L);
        assertThat(ledgerRows("big")).isEqualTo(2);
    }

    @Test
    void purchaseDeductsAndRecords() {
        Tables.seed(jdbc, "widget", 10);

        assertThat(service.purchase("widget", 4)).isEqualTo(new StockOutcome.Ok(6));

        assertThat(row("widget")).containsEntry("quantity", 6L).containsEntry("version", 2L);
        assertThat(jdbc.sql("SELECT quantity_delta FROM inventory_ledger WHERE sku_id = 'widget' AND reason = 'purchase'")
                .query(Long.class).single()).isEqualTo(-4);
    }

    @Test
    void purchaseNeverOversellsAndWritesNothingWhenShort() {
        Tables.seed(jdbc, "widget", 3);

        assertThat(service.purchase("widget", 4)).isEqualTo(new StockOutcome.Insufficient());
        assertThat(service.purchase("widget", 3)).isEqualTo(new StockOutcome.Ok(0));
        assertThat(service.purchase("widget", 1)).isEqualTo(new StockOutcome.Insufficient());

        assertThat(row("widget")).containsEntry("quantity", 0L);
        assertThat(ledgerRows("widget")).isEqualTo(2);
        assertThat(service.find("widget")).as("G5: a SKU at 0 keeps its row").contains(new InventoryItem("widget", 0));
    }

    @Test
    void purchaseOfUnknownSkuIsNotFound() {
        assertThat(service.purchase("ghost", 1)).isEqualTo(new StockOutcome.NotFound());
        assertThat(service.find("ghost")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
    }

    /** G1: case-sensitive ids are different SKUs. */
    @Test
    void skuIdsAreCaseSensitive() {
        service.add("ABC", 1);
        service.add("abc", 2);
        assertThat(service.find("ABC")).contains(new InventoryItem("ABC", 1));
        assertThat(service.find("abc")).contains(new InventoryItem("abc", 2));
    }

    /** S2, G11: malformed ids are rejected before any I/O. */
    @Test
    void malformedSkuIdIsRejectedBeforeAnyWrite() {
        assertThat(service.add("bad/id", 1)).isEqualTo(new WriteResult.InvalidRequest());
        assertThat(service.purchase("bad/id", 1)).isEqualTo(new StockOutcome.NotFound());
        assertThat(service.find("bad/id")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveQuantityIsAProgrammingError(int quantity) {
        assertThatThrownBy(() -> service.add("widget", quantity)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.purchase("widget", quantity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Recorded (DESIGN-V2 §1): the balance row always equals the ledger sum. */
    @Test
    void balanceEqualsLedgerSum() {
        service.add("a", 5);
        service.add("a", 6);
        service.purchase("a", 4);
        service.purchase("a", 7);
        service.purchase("a", 1);

        assertThat(row("a")).containsEntry("quantity", 0L).containsEntry("version", 4L);
        assertThat(ledgerRows("a")).as("the rejected purchase wrote nothing").isEqualTo(4);
    }

    /**
     * DESIGN-V2 §9: a read is one autocommit SELECT that never waits on a writer. A second connection holds the row
     * lock of an uncommitted purchase; find() on the test thread still answers the committed value within a bound.
     */
    @Test
    void findNeverWaitsOnAWriterHoldingTheRowLock() throws Exception {
        Tables.seed(jdbc, "locked", 5);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        javax.sql.DataSource dataSource = this.dataSource;
        Thread writer = new Thread(() -> {
            try (java.sql.Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE sku SET quantity = quantity - 1, version = version + 1 "
                            + "WHERE sku_id = 'locked'");
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
            assertThat(service.find("locked")).contains(new InventoryItem("locked", 5));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("a read must not wait on the writer's lock").isLessThan(java.time.Duration.ofSeconds(5));
        } finally {
            release.countDown();
            writer.join(30_000);
        }
    }
}
