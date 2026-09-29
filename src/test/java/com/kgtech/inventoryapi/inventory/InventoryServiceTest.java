package com.kgtech.inventoryapi.inventory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * S11, E1, G1, G5, G12, V2, S2 through the service against Postgres, without an Idempotency-Key. Not @Transactional:
 * with no surrounding transaction the service's TransactionTemplate (READ COMMITTED, REQUIRED) starts its own, so
 * seed rows are committed first (autocommit JdbcClient, Tables.seed); the tables are reset before each test.
 */
@IntegrationTest
class InventoryServiceTest {

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    private static String newSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The balance row: quantity and version. */
    private Map<String, Object> row(String sku) {
        return jdbc.sql("SELECT quantity, version FROM sku WHERE sku_id = ?").param(sku).query().singleRow();
    }

    private long skuRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long ledgerRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?").param(sku).query(Long.class).single();
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
    void addCreatesTheSkuRowAndFirstLedgerRow() {
        assertThat(service.add("widget", 5, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(5)));

        assertThat(row("widget")).containsEntry("quantity", 5L).containsEntry("version", 1L);
        List<Map<String, Object>> rows = ledger("widget");
        assertThat(rows).extracting(InventoryServiceTest::delta).containsExactly(5L);
        assertThat(rows).extracting(r -> r.get("reason")).containsExactly("add");
        assertThat(service.find("widget")).contains(new InventoryItem("widget", 5));
    }

    @Test
    void addAccumulatesAndBumpsTheVersion() {
        service.add("widget", 5, null);
        assertThat(service.add("widget", 7, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(12)));

        assertThat(skuRows("widget")).isEqualTo(1);
        assertThat(row("widget")).containsEntry("quantity", 12L).containsEntry("version", 2L);
        List<Map<String, Object>> rows = ledger("widget");
        assertThat(rows).extracting(InventoryServiceTest::delta).containsExactly(5L, 7L);
        assertThat(rows).extracting(r -> r.get("reason")).containsExactly("add", "add");
    }

    /** V2: the returned and stored balance is a long, past the int range of a request quantity. */
    @Test
    void addReturnsBalanceAboveIntMax() {
        String sku = newSku("aboveint");

        assertThat(service.add(sku, Integer.MAX_VALUE, null))
                .isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(Integer.MAX_VALUE)));
        assertThat(service.add(sku, Integer.MAX_VALUE, null))
                .isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(4_294_967_294L)));

        assertThat(row(sku)).containsEntry("quantity", 4_294_967_294L);
    }

    /**
     * G12, S11: an add that lands exactly on 9223372036854775807 is accepted; one above is Overflow (a 400) and writes
     * nothing: no ledger row and no version change. The balance is seeded, since the API can't reach the limit.
     */
    @Test
    void addRejectsOverflowWithoutWriting() {
        Tables.seed(jdbc, "big", Long.MAX_VALUE - 5);

        assertThat(service.add("big", 5, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(Long.MAX_VALUE)));
        assertThat(service.add("big", 1, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Overflow()));
        assertThat(service.add("big", Integer.MAX_VALUE, null))
                .isEqualTo(new WriteResult.Done<>(new StockOutcome.Overflow()));

        assertThat(row("big")).containsEntry("quantity", Long.MAX_VALUE).containsEntry("version", 2L);
        assertThat(ledgerRows("big")).isEqualTo(2);
    }

    @Test
    void purchaseDeductsAndRecords() {
        Tables.seed(jdbc, "widget", 10);

        assertThat(service.purchase("widget", 4, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(6)));

        assertThat(row("widget")).containsEntry("quantity", 6L).containsEntry("version", 2L);
        assertThat(jdbc.sql("SELECT quantity_delta FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'")
                .param("widget").query(Long.class).single()).isEqualTo(-4);
    }

    @Test
    void purchaseNeverOversellsAndWritesNothingWhenShort() {
        Tables.seed(jdbc, "widget", 3);

        assertThat(service.purchase("widget", 4, null))
                .isEqualTo(new WriteResult.Done<>(new StockOutcome.Insufficient()));
        assertThat(service.purchase("widget", 3, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(0)));
        assertThat(service.purchase("widget", 1, null))
                .isEqualTo(new WriteResult.Done<>(new StockOutcome.Insufficient()));

        assertThat(row("widget")).containsEntry("quantity", 0L).containsEntry("version", 2L);
        assertThat(ledgerRows("widget")).isEqualTo(2);
        assertThat(service.find("widget")).as("G5: a SKU at 0 keeps its row").contains(new InventoryItem("widget", 0));
    }

    /** G5: buying the whole stock answers 0 and keeps the SKU. */
    @Test
    void purchaseOfExactStockReturnsZeroAndKeepsSku() {
        String sku = newSku("buyall");
        Tables.seed(jdbc, sku, 4);

        assertThat(service.purchase(sku, 4, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(0)));

        assertThat(skuRows(sku)).isEqualTo(1);
        assertThat(ledgerRows(sku)).isEqualTo(2);
        assertThat(row(sku)).containsEntry("quantity", 0L);
    }

    /** A SKU row with quantity 0 and no ledger rows (a bare row, as V3 leaves one) is Insufficient, not NotFound. */
    @Test
    void purchaseOnSkuWithZeroBalanceReturnsInsufficient() {
        String sku = newSku("bare");
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(sku).update();

        assertThat(service.purchase(sku, 1, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Insufficient()));

        assertThat(row(sku)).containsEntry("quantity", 0L).containsEntry("version", 0L);
        assertThat(ledgerRows(sku)).isZero();
    }

    @Test
    void purchaseOfUnknownSkuIsNotFound() {
        assertThat(service.purchase("ghost", 1, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.NotFound()));

        assertThat(service.find("ghost")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
        assertThat(ledgerRows("ghost")).isZero();
    }

    /**
     * AC1, E1 (OQ-2 B): a purchase racing a create of the same new SKU that has not committed answers NotFound, as it
     * always has, and does not wait for the create. The create's balance row (quantity 10) and ledger row are held
     * uncommitted on another connection.
     */
    @Test
    void purchaseRacingAnUncommittedCreateIsNotFound() throws Exception {
        String sku = newSku("racing");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection create = dataSource.getConnection()) {
            create.setAutoCommit(false);
            try (PreparedStatement insertSku = create.prepareStatement(
                            "INSERT INTO sku (sku_id, quantity, version) VALUES (?, 10, 1)");
                    PreparedStatement insertLedger = create.prepareStatement(
                            "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, 10, 'add')")) {
                insertSku.setString(1, sku);
                insertSku.executeUpdate();
                insertLedger.setString(1, sku);
                insertLedger.executeUpdate();
            }

            Future<WriteResult<StockOutcome.Purchase>> purchase = pool.submit(() -> service.purchase(sku, 1, null));

            assertThat(purchase.get(5, SECONDS)).as("answered while the create is uncommitted")
                    .isEqualTo(new WriteResult.Done<>(new StockOutcome.NotFound()));
            create.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(skuRows(sku)).isZero();
        assertThat(ledgerRows(sku)).isZero();
    }

    /** V2: an int-sized purchase from a long-sized balance. */
    @Test
    void purchaseOfIntMaxFromLongMaxStock() {
        String sku = newSku("bigbuy");
        Tables.seed(jdbc, sku, Long.MAX_VALUE);

        assertThat(service.purchase(sku, Integer.MAX_VALUE, null))
                .isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(Long.MAX_VALUE - 2_147_483_647L)));

        assertThat(row(sku)).containsEntry("quantity", Long.MAX_VALUE - 2_147_483_647L);
    }

    /** G1: skuIds differing only in case are different SKUs. */
    @Test
    void skuIdsAreCaseSensitive() {
        assertThat(service.add("ABC", 3, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(3)));
        assertThat(service.add("abc", 5, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(5)));
        assertThat(service.purchase("ABC", 3, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(0)));

        assertThat(service.find("ABC")).contains(new InventoryItem("ABC", 0));
        assertThat(service.find("abc")).contains(new InventoryItem("abc", 5));
        assertThat(service.purchase("Abc", 1, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.NotFound()));
        assertThat(skuRows("Abc")).isZero();
    }

    /** S2, G11: a malformed skuId is an outcome value (create 400, purchase 404), answered before any write. */
    @ParameterizedTest
    @ValueSource(strings = {"-bad", "a!b", "a b", ".dot", "bad/id"})
    void malformedSkuIdIsRejectedBeforeAnyWrite(String skuId) {
        assertThat(service.add(skuId, 1, null)).isEqualTo(new WriteResult.InvalidRequest<>());
        assertThat(service.purchase(skuId, 1, null)).isEqualTo(new WriteResult.Done<>(new StockOutcome.NotFound()));
        assertThat(service.find(skuId)).isEmpty();

        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isZero();
    }

    /** The controller's @Valid makes this unreachable over HTTP; the service still refuses it and writes nothing. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void nonPositiveQuantityIsAProgrammingError(int quantity) {
        String fresh = newSku("nonpos-new");
        String stocked = newSku("nonpos-old");
        Tables.seed(jdbc, stocked, 10);

        assertThatThrownBy(() -> service.add(fresh, quantity, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(stocked, quantity, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.purchase(stocked, quantity, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(skuRows(fresh)).isZero();
        assertThat(ledgerRows(stocked)).isEqualTo(1);
        assertThat(row(stocked)).containsEntry("quantity", 10L).containsEntry("version", 1L);
    }

    /** A14: the balance row always equals the ledger sum; a rejected purchase writes nothing. */
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

    /**
     * E1: a read is one autocommit SELECT that never waits on a writer. A second connection holds the row lock of an
     * uncommitted purchase; find() on the test thread still answers the committed value within a bound.
     */
    @Test
    void findNeverWaitsOnAWriterHoldingTheRowLock() throws Exception {
        Tables.seed(jdbc, "locked", 5);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE sku SET quantity = quantity - 1, version = version + 1 "
                            + "WHERE sku_id = 'locked'");
                }
                locked.countDown();
                release.await(30, SECONDS);
                connection.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writer.start();
        assertThat(locked.await(30, SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            assertThat(service.find("locked")).contains(new InventoryItem("locked", 5));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("a read must not wait on the writer's lock").isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
            writer.join(30_000);
        }
    }

    /**
     * E1 (READ COMMITTED re-evaluation): purchase A holds the row lock inside an outer READ COMMITTED transaction,
     * which the service's template joins. Purchase B of the same stock waits on that lock, then re-checks its WHERE
     * against A's committed row: A gets Ok(0), B gets Insufficient, nothing throws and one purchase is recorded.
     */
    @Test
    void aWaitingPurchaseSeesTheCommittedBalance() throws Exception {
        String sku = newSku("wait");
        Tables.seed(jdbc, sku, 5);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CountDownLatch purchased = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WriteResult<StockOutcome.Purchase>> first = pool.submit(() -> outer.execute(status -> {
                WriteResult<StockOutcome.Purchase> result = service.purchase(sku, 5, null);
                purchased.countDown();
                awaitOrFail(release);
                return result;
            }));
            assertThat(purchased.await(30, SECONDS)).as("A holds the row lock").isTrue();

            Future<WriteResult<StockOutcome.Purchase>> second = pool.submit(() -> service.purchase(sku, 5, null));

            assertThatThrownBy(() -> second.get(500, MILLISECONDS)).as("B waits on A's row lock")
                    .isInstanceOf(TimeoutException.class);
            awaitLockWaiter();
            release.countDown();
            assertThat(first.get(30, SECONDS)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Ok(0)));
            assertThat(second.get(30, SECONDS)).isEqualTo(new WriteResult.Done<>(new StockOutcome.Insufficient()));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(row(sku)).containsEntry("quantity", 0L).containsEntry("version", 2L);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'")
                .param(sku).query(Long.class).single()).isEqualTo(1);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
        assertThat(Invariants.minQuantity(jdbc)).isNotNegative();
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(30, SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** B's UPDATE is blocked on a row lock in Postgres, so A's commit is what it re-evaluates against. */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'").query(Long.class).single() == 0) {
            assertThat(System.nanoTime()).as("a backend waits on A's row lock").isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
