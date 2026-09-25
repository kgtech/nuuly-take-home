package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * AC1–AC4, G1, G5, G12, V2 through the service against Postgres (S11). Not @Transactional: the service opens its
 * own REQUIRES_NEW transaction, so seed rows are committed first (autocommit JdbcClient) and removed afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryServiceTest {

    @Autowired
    InventoryService service;

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

    private String newSku(String prefix) {
        String sku = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        skus.add(sku);
        return sku;
    }

    private void seedSku(String sku) {
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(sku).update();
    }

    private void seedLedger(String sku, long delta) {
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, ?)")
                .params(sku, delta, delta > 0 ? "add" : "purchase")
                .update();
    }

    private void seedStock(String sku, long quantity) {
        seedSku(sku);
        seedLedger(sku, quantity);
    }

    private long skuRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long ledgerRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long balance(String sku) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?")
                .param(sku)
                .query(Long.class)
                .single();
    }

    private List<Map<String, Object>> ledger(String sku) {
        return jdbc.sql("SELECT quantity_delta, reason FROM inventory_ledger WHERE sku_id = ? ORDER BY id")
                .param(sku)
                .query()
                .listOfRows();
    }

    private static long delta(Map<String, Object> row) {
        return ((Number) row.get("quantity_delta")).longValue();
    }

    @Test
    void addCreatesSkuRowAndFirstLedgerRow() {
        String sku = newSku("create");

        StockOutcome.Add outcome = service.add(sku, 5);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(5));
        assertThat(skuRows(sku)).isEqualTo(1);
        List<Map<String, Object>> rows = ledger(sku);
        assertThat(rows).hasSize(1);
        assertThat(delta(rows.get(0))).isEqualTo(5);
        assertThat(rows.get(0).get("reason")).isEqualTo("add");
    }

    @Test
    void addToExistingSkuAppendsSecondRowAndReturnsSum() {
        String sku = newSku("addsum");

        assertThat(service.add(sku, 5)).isEqualTo(new StockOutcome.Ok(5));
        StockOutcome.Add outcome = service.add(sku, 7);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(12));
        assertThat(skuRows(sku)).isEqualTo(1);
        List<Map<String, Object>> rows = ledger(sku);
        assertThat(rows).extracting(InventoryServiceTest::delta).containsExactly(5L, 7L);
        assertThat(rows).extracting(row -> row.get("reason")).containsExactly("add", "add");
    }

    @Test
    void addReturnsBalanceAboveIntMax() {
        String sku = newSku("aboveint");

        assertThat(service.add(sku, Integer.MAX_VALUE)).isEqualTo(new StockOutcome.Ok(Integer.MAX_VALUE));
        StockOutcome.Add outcome = service.add(sku, Integer.MAX_VALUE);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(4_294_967_294L));
        assertThat(balance(sku)).isEqualTo(4_294_967_294L);
    }

    /** G12: an add that lands exactly on 9223372036854775807 is accepted. */
    @ParameterizedTest
    @ValueSource(ints = {10, Integer.MAX_VALUE})
    void addUpToLongMaxSucceeds(int quantity) {
        String sku = newSku("tomax");
        seedStock(sku, Long.MAX_VALUE - quantity);

        StockOutcome.Add outcome = service.add(sku, quantity);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(Long.MAX_VALUE));
        assertThat(ledgerRows(sku)).isEqualTo(2);
        assertThat(balance(sku)).isEqualTo(Long.MAX_VALUE);
    }

    static Stream<Arguments> overflowCases() {
        return Stream.of(
                // seed, add that reaches the seed + add below or at max (0 = none), overflowing add, ledger rows after
                Arguments.of(Long.MAX_VALUE - 10, 10, 1, 2, Long.MAX_VALUE),
                Arguments.of(Long.MAX_VALUE - 9, 0, 10, 1, Long.MAX_VALUE - 9),
                Arguments.of(Long.MAX_VALUE, 0, Integer.MAX_VALUE, 1, Long.MAX_VALUE));
    }

    /** G12: an add that would pass 9223372036854775807 returns Overflow and writes no ledger row. */
    @ParameterizedTest
    @MethodSource("overflowCases")
    void addOneAboveLongMaxReturnsOverflowAndInsertsNothing(long seed, int firstAdd, int overflowingAdd,
            long expectedRows, long expectedBalance) {
        String sku = newSku("overmax");
        seedStock(sku, seed);
        if (firstAdd > 0) {
            assertThat(service.add(sku, firstAdd)).isEqualTo(new StockOutcome.Ok(Long.MAX_VALUE));
        }

        StockOutcome.Add outcome = service.add(sku, overflowingAdd);

        assertThat(outcome).isEqualTo(new StockOutcome.Overflow());
        assertThat(ledgerRows(sku)).isEqualTo(expectedRows);
        assertThat(balance(sku)).isEqualTo(expectedBalance);
        assertThat(skuRows(sku)).isEqualTo(1);
    }

    @Test
    void purchaseReturnsRemainingAsLong() {
        String sku = newSku("buy");
        seedStock(sku, 10);

        StockOutcome.Purchase outcome = service.purchase(sku, 3);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(7));
        List<Map<String, Object>> rows = ledger(sku);
        assertThat(rows).hasSize(2);
        assertThat(delta(rows.get(1))).isEqualTo(-3);
        assertThat(rows.get(1).get("reason")).isEqualTo("purchase");
        assertThat(balance(sku)).isEqualTo(7);
    }

    @Test
    void purchaseOfExactStockReturnsZeroAndKeepsSku() {
        String sku = newSku("buyall");
        seedStock(sku, 4);

        StockOutcome.Purchase outcome = service.purchase(sku, 4);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(0));
        assertThat(skuRows(sku)).isEqualTo(1);
        assertThat(ledgerRows(sku)).isEqualTo(2);
        assertThat(balance(sku)).isZero();
    }

    @Test
    void purchaseAboveStockReturnsInsufficientAndInsertsNothing() {
        String sku = newSku("short");
        seedStock(sku, 2);

        StockOutcome.Purchase outcome = service.purchase(sku, 3);

        assertThat(outcome).isEqualTo(new StockOutcome.Insufficient());
        assertThat(ledgerRows(sku)).isEqualTo(1);
        assertThat(balance(sku)).isEqualTo(2);
    }

    @Test
    void purchaseOnSkuWithNoLedgerRowsReturnsInsufficient() {
        String sku = newSku("bare");
        seedSku(sku);

        StockOutcome.Purchase outcome = service.purchase(sku, 1);

        assertThat(outcome).isEqualTo(new StockOutcome.Insufficient());
        assertThat(ledgerRows(sku)).isZero();
        assertThat(skuRows(sku)).isEqualTo(1);
    }

    @Test
    void purchaseOfMissingSkuReturnsNotFoundAndInsertsNothing() {
        String sku = newSku("missing");

        StockOutcome.Purchase outcome = service.purchase(sku, 1);

        assertThat(outcome).isEqualTo(new StockOutcome.NotFound());
        assertThat(skuRows(sku)).isZero();
        assertThat(ledgerRows(sku)).isZero();
    }

    @Test
    void purchaseOfIntMaxFromLongMaxStock() {
        String sku = newSku("bigbuy");
        seedStock(sku, Long.MAX_VALUE);

        StockOutcome.Purchase outcome = service.purchase(sku, Integer.MAX_VALUE);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(Long.MAX_VALUE - 2_147_483_647L));
        assertThat(balance(sku)).isEqualTo(Long.MAX_VALUE - 2_147_483_647L);
    }

    /** G1: skuIds differing only in case are different SKUs. */
    @Test
    void skuIdsAreCaseSensitive() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String upper = "ABC" + suffix;
        String lower = "abc" + suffix;
        String mixed = "Abc" + suffix;
        skus.addAll(List.of(upper, lower, mixed));

        assertThat(service.add(upper, 3)).isEqualTo(new StockOutcome.Ok(3));
        assertThat(service.add(lower, 5)).isEqualTo(new StockOutcome.Ok(5));
        assertThat(service.purchase(upper, 3)).isEqualTo(new StockOutcome.Ok(0));

        assertThat(balance(upper)).isZero();
        assertThat(balance(lower)).isEqualTo(5);
        assertThat(service.purchase(mixed, 1)).isEqualTo(new StockOutcome.NotFound());
        assertThat(skuRows(mixed)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void nonPositiveQuantityIsRejectedBeforeDatabase(int quantity) {
        String fresh = newSku("nonpos-new");
        String stocked = newSku("nonpos-old");
        seedStock(stocked, 10);

        assertThatThrownBy(() -> service.add(fresh, quantity)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(stocked, quantity)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.purchase(stocked, quantity)).isInstanceOf(IllegalArgumentException.class);

        assertThat(skuRows(fresh)).isZero();
        assertThat(ledgerRows(fresh)).isZero();
        assertThat(ledgerRows(stocked)).isEqualTo(1);
        assertThat(balance(stocked)).isEqualTo(10);
    }
}
