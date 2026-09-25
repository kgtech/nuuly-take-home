package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * AC6–AC8, W2, X1, Y2: real Postgres errors raised by {@link LedgerFaultTrigger} go through the driver, Spring's
 * translation and the @Retryable interceptor. Not @Transactional; seed rows are committed before the trigger is
 * installed, and trigger, function, sequences and rows are removed afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StockWriteRetryTest {

    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final int MAX_RETRIES = 10;
    private static final Pattern STACK_FRAME = Pattern.compile("^\\s+at \\S");

    /** The two stock writes; purchase runs against 10 seeded units, add against a new SKU. */
    enum Op {
        ADD((service, sku) -> service.add(sku, 4, null), 0, 4),
        PURCHASE((service, sku) -> service.purchase(sku, 4, null), 10, 6);

        final BiFunction<InventoryService, String, WriteResult> call;
        final long seed;
        final long expectedBalance;

        Op(BiFunction<InventoryService, String, WriteResult> call, long seed, long expectedBalance) {
            this.call = call;
            this.seed = seed;
            this.expectedBalance = expectedBalance;
        }
    }

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private LedgerFaultTrigger fault;
    private final List<String> skus = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fault = new LedgerFaultTrigger(jdbcTemplate);
        fault.drop(); // in case an earlier run was killed before its @AfterEach
    }

    @AfterEach
    void cleanUp() {
        fault.drop();
        for (String sku : skus) {
            jdbc.sql("DELETE FROM inventory_ledger WHERE sku_id = ?").param(sku).update();
            jdbc.sql("DELETE FROM sku WHERE sku_id = ?").param(sku).update();
        }
    }

    private String newSku(String prefix, long seed) {
        String sku = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        skus.add(sku);
        if (seed > 0) {
            jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(sku).update();
            jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, 'add')")
                    .params(sku, seed)
                    .update();
        }
        return sku;
    }

    private long ledgerRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long skuRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long lastRowXmin(String sku) {
        return jdbc.sql("SELECT xmin::text::bigint FROM inventory_ledger WHERE sku_id = ? ORDER BY id DESC LIMIT 1")
                .param(sku)
                .query(Long.class)
                .single();
    }

    private static void assertRootSqlState(Throwable thrown, String sqlState) {
        assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                .isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo(sqlState));
    }

    private void assertRetriedOnceInNewTransaction(Op op, String sku, long rowsBefore) {
        WriteResult outcome = op.call.apply(service, sku);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(op.expectedBalance));
        assertThat(fault.attempts()).isEqualTo(2);
        assertThat(ledgerRows(sku)).isEqualTo(rowsBefore + 1);
        assertThat(lastRowXmin(sku)).isNotEqualTo(fault.failedTxid());
    }

    /** AC6: a 40001 raised by the ledger INSERT is retried, and the retry commits in a different transaction. */
    @ParameterizedTest
    @EnumSource(Op.class)
    void forced40001OnFirstAttemptIsRetriedInNewTransaction(Op op) {
        String sku = newSku("retry-" + op.name().toLowerCase(), op.seed);
        long rowsBefore = ledgerRows(sku);
        fault.failOnInsert(sku, 1, SERIALIZATION_FAILURE);

        assertRetriedOnceInNewTransaction(op, sku, rowsBefore);
    }

    /** AC6 / plan OQ5: a 40001 raised at commit reaches the retry as a PessimisticLockingFailureException. */
    @Test
    void forced40001AtCommitIsRetried() {
        String sku = newSku("retry-commit", 0);
        fault.failOnCommit(sku, 1, SERIALIZATION_FAILURE);

        assertRetriedOnceInNewTransaction(Op.ADD, sku, 0);
        assertThat(skuRows(sku)).isEqualTo(1);
    }

    @Test
    void forced40P01IsRetried() {
        String sku = newSku("retry-deadlock", 0);
        fault.failOnInsert(sku, 1, DEADLOCK_DETECTED);

        WriteResult outcome = service.add(sku, 4, null);

        assertThat(outcome).isEqualTo(new StockOutcome.Ok(4));
        assertThat(fault.attempts()).isEqualTo(2);
        assertThat(ledgerRows(sku)).isEqualTo(1);
    }

    /**
     * AC7: 55P03 is not a serialization failure, so it is not retried. Through JdbcClient it arrives as a
     * DataAccessException that is not necessarily a PessimisticLockingFailureException (see StockWriteRetryFilterTest).
     */
    @Test
    void lockNotAvailableIsNotRetried(CapturedOutput output) {
        String sku = newSku("noretry", 0);
        fault.failOnInsert(sku, 1000, LOCK_NOT_AVAILABLE);

        assertThatThrownBy(() -> service.add(sku, 4, null))
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                        .isInstanceOfSatisfying(SQLException.class,
                                sql -> assertThat(sql.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE)));

        assertThat(fault.attempts()).isEqualTo(1);
        assertThat(ledgerRows(sku)).isZero();
        assertThat(skuRows(sku)).isZero();
        List<String> lines = output.getAll().lines().toList();
        String warn = "Stock write failed without retry for SKU " + sku + " (SQLState " + LOCK_NOT_AVAILABLE + ")";
        List<Integer> warnAt = IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).contains(warn))
                .boxed()
                .toList();
        assertThat(warnAt).as(output.getAll()).hasSize(1);
        assertThat(lines.get(warnAt.get(0))).contains("WARN");
        assertThat(lines).as(output.getAll()).noneMatch(line -> line.contains("ERROR") && line.contains(sku));
        assertThat(lines.subList(warnAt.get(0) + 1, lines.size()))
                .as("no stack trace after the WARN line")
                .noneMatch(line -> line.contains("SQLException") || STACK_FRAME.matcher(line).find());
    }

    /** AC8: after 10 retries the last exception escapes (story 3 maps it to 500) and the SKU is logged at ERROR. */
    @Test
    void exhaustedRetriesPropagateAndLogSku(CapturedOutput output) {
        String sku = newSku("exhausted", 0);
        fault.failOnInsert(sku, 1000, SERIALIZATION_FAILURE);

        assertThatThrownBy(() -> service.add(sku, 4, null))
                .satisfies(thrown -> assertRootSqlState(thrown, SERIALIZATION_FAILURE));

        assertThat(fault.attempts()).isEqualTo(MAX_RETRIES + 1);
        assertThat(ledgerRows(sku)).isZero();
        assertThat(skuRows(sku)).isZero();
        assertThat(output.getAll().lines())
                .as(output.getAll())
                .anyMatch(line -> line.contains("ERROR") && line.contains("Stock write retries exhausted for SKU " + sku));
    }

    /**
     * AC8, W2 with an Idempotency-Key: the retry wraps the @Idempotent interceptor, so every attempt claims again and
     * the exhausted retries log the SKU at ERROR. Nothing is stored against the key and no ledger row is written.
     */
    @Test
    void keyedExhaustedRetriesLogSku(CapturedOutput output) {
        String sku = newSku("keyed-exhausted", 0);
        String key = UUID.randomUUID().toString();
        fault.failOnInsert(sku, 1000, SERIALIZATION_FAILURE);

        assertThatThrownBy(() -> service.add(sku, 4, key))
                .satisfies(thrown -> assertRootSqlState(thrown, SERIALIZATION_FAILURE));

        assertThat(fault.attempts()).isEqualTo(MAX_RETRIES + 1);
        assertThat(ledgerRows(sku)).isZero();
        assertThat(skuRows(sku)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?::uuid")
                .param(key).query(Long.class).single()).isZero();
        assertThat(output.getAll().lines())
                .as(output.getAll())
                .anyMatch(line -> line.contains("ERROR") && line.contains("Stock write retries exhausted for SKU " + sku));
    }

    /** X1: both writes run at SERIALIZABLE; the trigger raises P0001 for a ledger insert at any other level. */
    @Test
    void writesRunAtSerializable() {
        String sku = newSku("isolation", 0);
        fault.failUnlessSerializable(sku);

        // the trigger is live: an autocommit (READ COMMITTED) insert is rejected
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(sku).update();
        assertThatThrownBy(() -> jdbc.sql(
                        "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, 1, 'add')")
                .param(sku)
                .update())
                .satisfies(thrown -> assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                        .isInstanceOfSatisfying(SQLException.class,
                                sql -> assertThat(sql.getSQLState()).isEqualTo("P0001")));

        assertThat(service.add(sku, 5, null)).isEqualTo(new StockOutcome.Ok(5));
        assertThat(service.purchase(sku, 2, null)).isEqualTo(new StockOutcome.Ok(3));
        assertThat(ledgerRows(sku)).isEqualTo(2);
    }
}
