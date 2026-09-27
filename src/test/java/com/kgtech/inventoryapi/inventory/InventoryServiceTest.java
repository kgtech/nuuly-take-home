package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
    RedisConnectionFactory redis;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
        Tables.flush(redis);
    }

    private Map<String, Object> row(String sku) {
        return jdbc.sql("SELECT quantity, version FROM sku WHERE sku_id = ?").param(sku).query().singleRow();
    }

    private long ledgerRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    @Test
    void addCreatesTheSkuRowAndFirstLedgerRow() {
        assertThat(service.add("widget", 5, null)).isEqualTo(new StockOutcome.Ok(5));

        assertThat(row("widget")).containsEntry("quantity", 5L).containsEntry("version", 1L);
        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertThat(service.find("widget")).contains(new InventoryItem("widget", 5));
    }

    @Test
    void addAccumulatesAndBumpsTheVersion() {
        service.add("widget", 5, null);
        assertThat(service.add("widget", 7, null)).isEqualTo(new StockOutcome.Ok(12));

        assertThat(row("widget")).containsEntry("quantity", 12L).containsEntry("version", 2L);
        assertThat(ledgerRows("widget")).isEqualTo(2);
    }

    /** G12: up to 2^63-1 is accepted; one more is Overflow and writes nothing. */
    @Test
    void addRejectsOverflowWithoutWriting() {
        Tables.seed(jdbc, "big", Long.MAX_VALUE - 5);

        assertThat(service.add("big", 5, null)).isEqualTo(new StockOutcome.Ok(Long.MAX_VALUE));
        assertThat(service.add("big", 1, null)).isEqualTo(new StockOutcome.Overflow());

        assertThat(row("big")).containsEntry("quantity", Long.MAX_VALUE).containsEntry("version", 2L);
        assertThat(ledgerRows("big")).isEqualTo(2);
    }

    @Test
    void purchaseDeductsAndRecords() {
        Tables.seed(jdbc, "widget", 10);

        assertThat(service.purchase("widget", 4, null)).isEqualTo(new StockOutcome.Ok(6));

        assertThat(row("widget")).containsEntry("quantity", 6L).containsEntry("version", 2L);
        assertThat(jdbc.sql("SELECT quantity_delta FROM inventory_ledger WHERE sku_id = 'widget' AND reason = 'purchase'")
                .query(Long.class).single()).isEqualTo(-4);
    }

    @Test
    void purchaseNeverOversellsAndWritesNothingWhenShort() {
        Tables.seed(jdbc, "widget", 3);

        assertThat(service.purchase("widget", 4, null)).isEqualTo(new StockOutcome.Insufficient());
        assertThat(service.purchase("widget", 3, null)).isEqualTo(new StockOutcome.Ok(0));
        assertThat(service.purchase("widget", 1, null)).isEqualTo(new StockOutcome.Insufficient());

        assertThat(row("widget")).containsEntry("quantity", 0L);
        assertThat(ledgerRows("widget")).isEqualTo(2);
        assertThat(service.find("widget")).as("G5: a SKU at 0 keeps its row").contains(new InventoryItem("widget", 0));
    }

    @Test
    void purchaseOfUnknownSkuIsNotFound() {
        assertThat(service.purchase("ghost", 1, null)).isEqualTo(new StockOutcome.NotFound());
        assertThat(service.find("ghost")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
    }

    /** G1: case-sensitive ids are different SKUs. */
    @Test
    void skuIdsAreCaseSensitive() {
        service.add("ABC", 1, null);
        service.add("abc", 2, null);
        assertThat(service.find("ABC")).contains(new InventoryItem("ABC", 1));
        assertThat(service.find("abc")).contains(new InventoryItem("abc", 2));
    }

    /** S2, G11: malformed ids are rejected before any I/O. */
    @Test
    void malformedSkuIdIsRejectedBeforeAnyWrite() {
        assertThat(service.add("bad/id", 1, null)).isEqualTo(new WriteResult.InvalidRequest());
        assertThat(service.purchase("bad/id", 1, null)).isEqualTo(new StockOutcome.NotFound());
        assertThat(service.find("bad/id")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveQuantityIsAProgrammingError(int quantity) {
        assertThatThrownBy(() -> service.add("widget", quantity, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.purchase("widget", quantity, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Recorded (DESIGN-V2 §1): the balance row always equals the ledger sum. */
    @Test
    void balanceEqualsLedgerSum() {
        service.add("a", 5, null);
        service.add("a", 6, null);
        service.purchase("a", 4, null);
        service.purchase("a", 7, null);
        service.purchase("a", 1, null);

        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
        assertThat(row("a")).containsEntry("quantity", 0L).containsEntry("version", 4L);
        assertThat(ledgerRows("a")).as("the rejected purchase wrote nothing").isEqualTo(4);
    }
}
