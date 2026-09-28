package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.kgtech.inventoryapi.Tables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.IntegrationTest;

/**
 * R2, G14, S8, T1, Y4: claim, replay, mismatch and expiry against Postgres, with {@code execute} run inside a
 * READ COMMITTED TransactionTemplate as the @Idempotent interceptor runs it (DESIGN-V2 §2). Not @Transactional: each
 * call commits.
 */
@IntegrationTest
class IdempotencyStoreTest {

    private static final StoredResponse OK = new StoredResponse(200, "application/json",
            "{\"skuId\":\"widget\",\"quantity\":5}");

    @Autowired
    IdempotencyStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private final AtomicInteger actionRuns = new AtomicInteger();

    @BeforeEach
    void setUp() {
        // test-only delete; the application never purges keys (R9)
        Tables.reset(jdbc);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        actionRuns.set(0);
    }

    private KeyedResult execute(IdempotentRequest request, Supplier<StoredResponse> action) {
        return transaction.execute(status -> store.execute(request, action));
    }

    private KeyedResult execute(IdempotentRequest request, StoredResponse response) {
        return execute(request, () -> {
            actionRuns.incrementAndGet();
            return response;
        });
    }

    private static IdempotentRequest add(UUID key, String skuId, int quantity) {
        return IdempotentRequest.of(key, Operation.ADD, skuId, quantity);
    }

    private Map<String, Object> row(UUID key) {
        return jdbc.sql("""
                SELECT operation, sku_id, request_hash, status, content_type, body
                FROM idempotency_keys WHERE idempotency_key = ?
                """).param(key).query().singleRow();
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single();
    }

    private void backdate(UUID key, String interval) {
        jdbc.sql("UPDATE idempotency_keys SET created_at = now() - ?::interval WHERE idempotency_key = ?")
                .params(interval, key)
                .update();
    }

    @Test
    void firstExecuteClaimsRunsActionAndStores() {
        UUID key = UUID.randomUUID();
        IdempotentRequest request = add(key, "widget", 5);

        assertThat(execute(request, OK)).isInstanceOfSatisfying(KeyedResult.Executed.class, e -> assertThat(e.response()).isEqualTo(OK));

        assertThat(actionRuns).hasValue(1);
        Map<String, Object> row = row(key);
        assertThat(row.get("operation")).isEqualTo("add");
        assertThat(row.get("sku_id")).isEqualTo("widget");
        assertThat((byte[]) row.get("request_hash")).isEqualTo(request.requestHash());
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
        assertThat(row.get("content_type")).isEqualTo("application/json");
        assertThat(row.get("body")).isEqualTo(OK.body());
    }

    @Test
    void sameRequestReplaysWithoutAction() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);

        KeyedResult replay = execute(add(key, "widget", 5), new StoredResponse(400, "text/plain", "changed"));

        assertThat(replay).isInstanceOfSatisfying(KeyedResult.Replayed.class, r -> assertThat(r.response()).isEqualTo(OK));
        assertThat(actionRuns).hasValue(1);
        assertThat(rows()).isEqualTo(1);
    }

    /** Y4: the stored status, Content-Type and body come back unchanged, errors included. */
    @Test
    void storedErrorReplaysUnchanged() {
        UUID key = UUID.randomUUID();
        StoredResponse notFound = new StoredResponse(404, "text/plain", "SKU not found");
        IdempotentRequest request = IdempotentRequest.of(key, Operation.PURCHASE, "ghost", 1);
        execute(request, notFound);

        assertThat(execute(request, OK)).isInstanceOfSatisfying(KeyedResult.Replayed.class, r -> assertThat(r.response()).isEqualTo(notFound));
        assertThat(actionRuns).hasValue(1);
    }

    private void assertRejectedAndUnchanged(UUID key, IdempotentRequest reuse) {
        Map<String, Object> before = row(key);

        assertThat(execute(reuse, new StoredResponse(200, "application/json", "other")))
                .isInstanceOf(KeyedResult.Rejected.class);

        assertThat(actionRuns).as("action runs").hasValue(1);
        Map<String, Object> after = row(key);
        assertThat((byte[]) after.get("request_hash")).isEqualTo((byte[]) before.get("request_hash"));
        after.remove("request_hash");
        before.remove("request_hash");
        assertThat(after).isEqualTo(before);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void differentHashRejected() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);

        assertRejectedAndUnchanged(key, add(key, "widget", 6));
    }

    @Test
    void differentSkuRejected() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);

        assertRejectedAndUnchanged(key, add(key, "gadget", 5));
    }

    /** G1: skuId comparison is case-sensitive. */
    @Test
    void differentSkuCaseRejected() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);

        assertRejectedAndUnchanged(key, add(key, "WIDGET", 5));
    }

    @Test
    void differentOperationRejected() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);

        assertRejectedAndUnchanged(key, IdempotentRequest.of(key, Operation.PURCHASE, "widget", 5));
    }

    /** T1: a key older than 24h is rejected even with the same request, and is never reused. */
    @Test
    void olderThan24hRejected() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);
        backdate(key, "24 hours 1 second");

        assertRejectedAndUnchanged(key, add(key, "widget", 5));
    }

    /**
     * T1's boundary: a key exactly 24h old (by the transaction's now()) still replays, so the lookup's comparison is
     * strict (mutating {@code <} to {@code <=} fails here; a longer interval fails olderThan24hRejected).
     */
    @Test
    void exactly24hStillReplays() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);

        KeyedResult result = transaction.execute(status -> {
            jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '24 hours' WHERE idempotency_key = ?")
                    .param(key).update();
            return store.execute(add(key, "widget", 5), () -> OK);
        });

        assertThat(result).isInstanceOfSatisfying(KeyedResult.Replayed.class, r -> assertThat(r.response()).isEqualTo(OK));
    }

    @Test
    void under24hReplays() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);
        backdate(key, "23 hours 59 minutes");

        assertThat(execute(add(key, "widget", 5), OK)).isInstanceOfSatisfying(KeyedResult.Replayed.class, r -> assertThat(r.response()).isEqualTo(OK));
        assertThat(actionRuns).hasValue(1);
    }

    /** A failure inside the action rolls back the claim: nothing is stored and the key stays usable (plan OQ3). */
    @Test
    void actionExceptionRollsBackClaim() {
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> execute(add(key, "widget", 5), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(rows()).isZero();
        assertThat(execute(add(key, "widget", 5), OK)).isInstanceOfSatisfying(KeyedResult.Executed.class, e -> assertThat(e.response()).isEqualTo(OK));
    }

    @Test
    void executeOutsideTransactionThrows() {
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> store.execute(add(key, "widget", 5), () -> {
            actionRuns.incrementAndGet();
            return OK;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(actionRuns).hasValue(0);
        assertThat(rows()).isZero();
    }

    /**
     * A committed row without a response is a tombstone: the README's clean-up cleared it (#56, C-16). The key stays
     * used up (400), whether or not the row is older than 24h, and the action never runs.
     */
    @Test
    void tombstonedRowIsRejectedNotReplayed() {
        UUID key = UUID.randomUUID();
        IdempotentRequest request = add(key, "widget", 5);
        execute(request, OK);
        jdbc.sql("UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL WHERE idempotency_key = ?")
                .param(key).update();

        assertThat(execute(request, OK)).isInstanceOf(KeyedResult.Rejected.class);
        backdate(key, "25 hours");
        assertThat(execute(request, OK)).isInstanceOf(KeyedResult.Rejected.class);
        assertThat(actionRuns).hasValue(1);
    }
}
