package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Issue #15 (R3-2), W2, S2, S3, S8: requests rejected before or at the idempotency claim return an outcome and never
 * throw, so no MethodRetryEvent is published and StockWriteFailureLogger logs nothing. Regression guard; the
 * positive controls force a non-retryable 55P03 so the recorded events and the log capture are shown to work. Not
 * {@code @Transactional}; rows and the fault trigger are removed afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@RecordApplicationEvents
class StockWriteRejectionEventsTest {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String MALFORMED_SKU = "-bad";

    @Autowired
    InventoryService service;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private LedgerFaultTrigger fault;
    private final List<String> skus = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fault = new LedgerFaultTrigger(jdbcTemplate);
        fault.drop(); // in case an earlier run was killed before its @AfterEach
    }

    @AfterEach
    void cleanUp() {
        fault.drop();
        for (String key : keys) {
            jdbc.sql("DELETE FROM idempotency_keys WHERE idempotency_key = ?::uuid").param(key).update();
        }
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

    private String newKey() {
        String key = UUID.randomUUID().toString();
        keys.add(key);
        return key;
    }

    private void assertNoRetryEventAndNoFailureLog(CapturedOutput output) {
        assertThat(events.stream(MethodRetryEvent.class).toList()).as("MethodRetryEvents").isEmpty();
        assertThat(output.getAll().lines())
                .as(output.getAll())
                .noneMatch(line -> line.contains("StockWriteFailureLogger")
                        || line.contains("Stock write failed without retry")
                        || line.contains("Stock write retries exhausted"));
    }

    private static void assertReturns(Supplier<WriteResult> call, WriteResult expected) {
        assertThat(call.get()).isEqualTo(expected);
    }

    /** S3: an empty or non-UUID key is rejected before the claim. */
    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "123e4567-e89b-12d3-a456-42661417400"})
    void malformedIdempotencyKeyPublishesNoRetryEvent(String key, CapturedOutput output) {
        String sku = newSku("badkey");

        assertReturns(() -> service.add(sku, 4, key), new WriteResult.InvalidRequest());
        assertReturns(() -> service.purchase(sku, 4, key), new WriteResult.InvalidRequest());

        assertNoRetryEventAndNoFailureLog(output);
    }

    /** S2, U3: with a valid key, a malformed skuId is rejected before the claim (create 400, purchase 404). */
    @Test
    void keyedMalformedSkuIdPublishesNoRetryEvent(CapturedOutput output) {
        assertReturns(() -> service.add(MALFORMED_SKU, 4, newKey()), new WriteResult.InvalidRequest());
        assertReturns(() -> service.purchase(MALFORMED_SKU, 4, newKey()), new StockOutcome.NotFound());

        assertNoRetryEventAndNoFailureLog(output);
    }

    /** S2: without a key, a malformed skuId is rejected with no I/O (create 400, purchase 404). */
    @Test
    void unkeyedMalformedSkuIdPublishesNoRetryEvent(CapturedOutput output) {
        assertReturns(() -> service.add(MALFORMED_SKU, 4, null), new WriteResult.InvalidRequest());
        assertReturns(() -> service.purchase(MALFORMED_SKU, 4, null), new StockOutcome.NotFound());

        assertNoRetryEventAndNoFailureLog(output);
    }

    /** S8: the same key with a different quantity, SKU or operation is rejected at the claim. */
    @Test
    void reusedKeyWithDifferentRequestPublishesNoRetryEvent(CapturedOutput output) {
        String sku = newSku("reused");
        String other = newSku("reused-other");
        String key = newKey();
        assertThat(service.add(sku, 4, key)).isInstanceOf(WriteResult.Stored.class);

        assertReturns(() -> service.add(sku, 5, key), new WriteResult.InvalidRequest());
        assertReturns(() -> service.add(other, 4, key), new WriteResult.InvalidRequest());
        assertReturns(() -> service.purchase(sku, 4, key), new WriteResult.InvalidRequest());

        assertNoRetryEventAndNoFailureLog(output);
    }

    /** Positive control: a non-retryable failure publishes an aborted MethodRetryEvent and logs one WARN. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonRetryableFailurePublishesRetryEventAndLogsWarn(boolean keyed, CapturedOutput output) {
        String sku = newSku("control");
        String key = keyed ? newKey() : null;
        fault.failOnInsert(sku, 1000, LOCK_NOT_AVAILABLE);

        assertThatThrownBy(() -> service.add(sku, 4, key)).isInstanceOf(DataAccessException.class);

        assertThat(events.stream(MethodRetryEvent.class).toList())
                .as("MethodRetryEvents")
                .anySatisfy(event -> {
                    assertThat(event.isRetryAborted()).isTrue();
                    assertThat(event.getMethod().getDeclaringClass()).isEqualTo(InventoryService.class);
                    assertThat(event.getSource().getArguments()[0]).isEqualTo(sku);
                });
        String warn = "Stock write failed without retry for SKU " + sku + " (SQLState " + LOCK_NOT_AVAILABLE + ")";
        assertThat(output.getAll().lines())
                .as(output.getAll())
                .anyMatch(line -> line.contains("WARN") && line.contains(warn));
    }
}
