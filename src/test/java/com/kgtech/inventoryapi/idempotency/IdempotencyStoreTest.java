package com.kgtech.inventoryapi.idempotency;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore.Keyed;

/**
 * R2, G14, S8, T1, Y4, A18, A33: claim, replay, mismatch and expiry against Postgres through {@code run}, which opens
 * its own READ COMMITTED transaction or joins a READ COMMITTED or DEFAULT caller's, and refuses a stricter one. A
 * concurrent claim of the same key waits for the first and replays it, or claims the key after a rollback (E1). Not
 * @Transactional: each call commits.
 */
@IntegrationTest
class IdempotencyStoreTest {

    private static final StoredResponse OK = new StoredResponse(200, "application/json",
            "{\"skuId\":\"widget\",\"quantity\":5}");
    /** How long the second of two concurrent runs must still be waiting (E1). */
    private static final long WAITING_MILLIS = 500;
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    IdempotencyStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private final AtomicInteger actionRuns = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() {
        // test-only delete; the application never purges keys (R9)
        Tables.reset(jdbc);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        actionRuns.set(0);
    }

    @AfterEach
    void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, SECONDS)).as("runs finished").isTrue();
    }

    /** A keyed write as InventoryService passes it: the spec POSTs' canonical request is the quantity's digits. */
    private record Request(UUID key, Operation operation, String skuId, String canonicalRequest) {

        byte[] requestHash() {
            return RequestHash.of(operation, skuId, canonicalRequest);
        }
    }

    private Keyed execute(Request request, Supplier<StoredResponse> action) {
        return store.run(request.key(), request.operation(), request.skuId(), request.canonicalRequest(), action);
    }

    private Keyed execute(Request request, StoredResponse response) {
        return execute(request, () -> {
            actionRuns.incrementAndGet();
            return response;
        });
    }

    private static Request add(UUID key, String skuId, int quantity) {
        return new Request(key, Operation.ADD, skuId, Integer.toString(quantity));
    }

    private static Request purchase(UUID key, String skuId, int quantity) {
        return new Request(key, Operation.PURCHASE, skuId, Integer.toString(quantity));
    }

    private static void assertResponse(Keyed result, StoredResponse expected) {
        assertThat(result).isEqualTo(new Keyed.Response(expected));
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
        Request request = add(key, "widget", 5);

        assertResponse(execute(request, OK), OK);

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

        Keyed replay = execute(add(key, "widget", 5), new StoredResponse(400, "text/plain", "changed"));

        assertResponse(replay, OK);
        assertThat(actionRuns).hasValue(1);
        assertThat(rows()).isEqualTo(1);
    }

    /** Y4: the stored status, Content-Type and body come back unchanged, errors included. */
    @Test
    void storedErrorReplaysUnchanged() {
        UUID key = UUID.randomUUID();
        StoredResponse notFound = new StoredResponse(404, "text/plain", "SKU not found");
        Request request = purchase(key, "ghost", 1);
        execute(request, notFound);

        assertResponse(execute(request, OK), notFound);
        assertThat(actionRuns).hasValue(1);
    }

    private void assertRejectedAndUnchanged(UUID key, Request reuse) {
        Map<String, Object> before = row(key);

        assertThat(execute(reuse, new StoredResponse(200, "application/json", "other")))
                .isEqualTo(new Keyed.Invalid());

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

        assertRejectedAndUnchanged(key, purchase(key, "widget", 5));
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

        Keyed result = transaction.execute(status -> {
            jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '24 hours' WHERE idempotency_key = ?")
                    .param(key).update();
            return execute(add(key, "widget", 5), () -> OK);
        });

        assertResponse(result, OK);
    }

    @Test
    void under24hReplays() {
        UUID key = UUID.randomUUID();
        execute(add(key, "widget", 5), OK);
        backdate(key, "23 hours 59 minutes");

        assertResponse(execute(add(key, "widget", 5), OK), OK);
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
        assertResponse(execute(add(key, "widget", 5), OK), OK);
    }

    /** Without a caller's transaction, run opens its own at READ COMMITTED and commits it (A33). */
    @Test
    void runOutsideATransactionOpensAReadCommittedOne() {
        UUID key = UUID.randomUUID();
        AtomicBoolean active = new AtomicBoolean();
        AtomicReference<Integer> isolation = new AtomicReference<>();

        assertResponse(execute(add(key, "widget", 5), () -> {
            active.set(TransactionSynchronizationManager.isActualTransactionActive());
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return OK;
        }), OK);

        assertThat(active).isTrue();
        assertThat(isolation).hasValue(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(rows()).isEqualTo(1);
    }

    /**
     * A33 (PROPAGATION_REQUIRED): inside a caller's transaction, run joins it instead of suspending it. The write runs
     * in the caller's Postgres transaction, and the caller's rollback takes the claim and the stored response with it.
     */
    @Test
    void runInsideAnOuterTransactionJoinsIt() {
        UUID key = UUID.randomUUID();
        AtomicLong writeTransaction = new AtomicLong();

        long outerTransaction = transaction.execute(status -> {
            long outer = currentTransactionId();
            execute(add(key, "widget", 5), () -> {
                writeTransaction.set(currentTransactionId());
                return OK;
            });
            assertThat(rows()).as("the claim, visible to the caller before it commits").isEqualTo(1);
            status.setRollbackOnly();
            return outer;
        });

        assertThat(writeTransaction).hasValue(outerTransaction);
        assertThat(rows()).as("rolled back with the caller").isZero();
    }

    /** A caller at ISOLATION_DEFAULT (Postgres's READ COMMITTED) is joined like a READ COMMITTED one (A33). */
    @Test
    void runJoinsADefaultIsolationOuterTransaction() {
        UUID key = UUID.randomUUID();
        AtomicLong writeTransaction = new AtomicLong();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        long outerTransaction = outer.execute(status -> {
            long id = currentTransactionId();
            assertResponse(execute(add(key, "widget", 5), () -> {
                writeTransaction.set(currentTransactionId());
                return OK;
            }), OK);
            return id;
        });

        assertThat(writeTransaction).hasValue(outerTransaction);
        assertThat(rows()).isEqualTo(1);
    }

    /**
     * A33: when joined, a write failure marks the caller's transaction rollback-only, so the caller cannot commit even
     * if it catches the exception (PROPAGATION_NESTED would roll back only to a savepoint and let the caller commit).
     */
    @Test
    void joinedWriteFailureMarksTheCallerRollbackOnly() {
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThatThrownBy(() -> execute(add(key, "widget", 5), () -> {
                throw new IllegalStateException("boom");
            })).hasMessage("boom");
            assertThat(status.isRollbackOnly()).as("caller marked rollback-only").isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rows()).isZero();
    }

    /**
     * A33: joining a stricter caller would run the claim at its isolation, where a concurrent claim of the same key
     * fails with 40001 instead of replaying, so run refuses it before any I/O and without joining. The checks run
     * inside the caller: after the caller rolls back, a claim written before the refusal would be gone too.
     */
    @ParameterizedTest
    @ValueSource(ints = {TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE})
    void runRefusesAStricterOuterTransaction(int isolation) {
        UUID key = UUID.randomUUID();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(isolation);

        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> execute(add(key, "widget", 5), OK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("IdempotencyStore.run joins only a READ COMMITTED transaction");
            assertThat(rows()).as("no claim, seen from inside the caller").isZero();
            assertThat(status.isRollbackOnly()).as("refused before joining").isFalse();
        });
        assertThat(actionRuns).hasValue(0);
    }

    private long currentTransactionId() {
        return jdbc.sql("SELECT txid_current()").query(Long.class).single();
    }

    /**
     * A18: a committed row without a stored response is never replayed and never a 500: the key stays used up
     * (Invalid, 400), whether or not the row is older than 24h, and the action never runs.
     */
    @Test
    void tombstonedRowIsRejectedNotReplayed() {
        UUID key = UUID.randomUUID();
        Request request = add(key, "widget", 5);
        execute(request, OK);
        jdbc.sql("UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL WHERE idempotency_key = ?")
                .param(key).update();

        assertThat(execute(request, OK)).isEqualTo(new Keyed.Invalid());
        backdate(key, "25 hours");
        assertThat(execute(request, OK)).isEqualTo(new Keyed.Invalid());
        assertThat(actionRuns).hasValue(1);
    }

    // ---- two runs with one fresh key, concurrently (R2, E1) ----

    /**
     * Waits until {@code run}'s write has started, so its claim row exists uncommitted. If run returns first, its result
     * (or exception) is the failure.
     */
    private static void awaitWriteStarted(CountDownLatch started, Future<Keyed> run) throws Exception {
        long deadline = System.nanoTime() + SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!started.await(10, MILLISECONDS)) {
            if (run.isDone()) {
                fail("run returned before its write started: " + run.get());
            }
            if (System.nanoTime() > deadline) {
                fail("the write did not start within " + TIMEOUT_SECONDS + " s");
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT_SECONDS, SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * R2, E1: at READ COMMITTED a second claim of a fresh key blocks on the first's uncommitted row, then finds the
     * committed response and replays it: its own write never runs and nothing fails with 40001.
     */
    @Test
    void concurrentClaimOfOneFreshKeyBlocksThenReplays() throws Exception {
        Request request = add(UUID.randomUUID(), "widget", 5);
        CountDownLatch firstWriting = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondWrites = new AtomicInteger();

        Future<Keyed> first = pool.submit(() -> execute(request, () -> {
            firstWriting.countDown();
            awaitRelease(releaseFirst);
            return OK;
        }));
        awaitWriteStarted(firstWriting, first);
        Future<Keyed> second = pool.submit(() -> execute(request, () -> {
            secondWrites.incrementAndGet();
            return new StoredResponse(400, "text/plain", "second");
        }));

        assertThatThrownBy(() -> second.get(WAITING_MILLIS, MILLISECONDS)).as("second run waits on the claim")
                .isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();

        assertResponse(first.get(TIMEOUT_SECONDS, SECONDS), OK);
        assertResponse(second.get(TIMEOUT_SECONDS, SECONDS), OK);
        assertThat(secondWrites).as("second write").hasValue(0);
        assertThat(rows()).isEqualTo(1);
        assertThat(row(request.key()).get("body")).isEqualTo(OK.body());
    }

    /** R2, E1: when the first run's write fails, its claim rolls back and the waiting run claims the key and writes. */
    @Test
    void concurrentClaimAfterARollbackClaimsAndWrites() throws Exception {
        Request request = add(UUID.randomUUID(), "widget", 5);
        StoredResponse secondResponse = new StoredResponse(200, "application/json", "{\"second\":true}");
        CountDownLatch firstWriting = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondWrites = new AtomicInteger();

        Future<Keyed> first = pool.submit(() -> execute(request, () -> {
            firstWriting.countDown();
            awaitRelease(releaseFirst);
            throw new IllegalStateException("boom");
        }));
        awaitWriteStarted(firstWriting, first);
        Future<Keyed> second = pool.submit(() -> execute(request, () -> {
            secondWrites.incrementAndGet();
            return secondResponse;
        }));

        assertThatThrownBy(() -> second.get(WAITING_MILLIS, MILLISECONDS)).as("second run waits on the claim")
                .isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();

        assertThatThrownBy(() -> first.get(TIMEOUT_SECONDS, SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertResponse(second.get(TIMEOUT_SECONDS, SECONDS), secondResponse);
        assertThat(secondWrites).as("second write").hasValue(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(row(request.key()).get("body")).isEqualTo(secondResponse.body());
    }
}
