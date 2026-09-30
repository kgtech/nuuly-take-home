package com.kgtech.inventoryapi.idempotency;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a keyed write once (R2, G14, A33): claims the Idempotency-Key, runs the write and stores its response in one
 * transaction. run opens a READ COMMITTED transaction, or joins a READ COMMITTED or DEFAULT caller's transaction and
 * refuses any other isolation (IllegalStateException); when joined, the caller's now() (T1) and rollback scope apply,
 * so a write failure marks the caller's transaction rollback-only. A concurrent claim of the same key blocks on the
 * primary key until the first transaction ends, then finds the committed row and replays it, or claims the key if the
 * first rolled back (E1). A {@code @Component}, not a {@code @Repository}: persistence exception translation would turn
 * the write's own exceptions into InvalidDataAccessApiUsageException.
 */
@Component
public class IdempotencyStore {

    /** A keyed write's answer: the stored response, first run or replay alike, or 400 "Invalid request". */
    public sealed interface Keyed {

        record Response(StoredResponse response) implements Keyed {
        }

        /** Different operation, skuId or request (S8), older than 24h (T1), or no stored response (A18). */
        record Invalid() implements Keyed {
        }
    }

    /** R2: claim the key; no row back means it already exists. */
    static final String CLAIM = """
            INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash)
            VALUES (:key, :operation, :skuId, :hash)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING idempotency_key
            """;

    /** T1: how long a key stays valid, by the database clock. */
    static final Duration KEY_VALIDITY = Duration.ofHours(24);

    /** T1: expiry uses the database clock (transaction start time) and KEY_VALIDITY. */
    static final String STORED = """
            SELECT operation, sku_id, request_hash, status, content_type, body,
                   created_at < now() - make_interval(secs => :validitySeconds) AS expired
            FROM idempotency_keys WHERE idempotency_key = :key
            """;

    /** Y4: the response columns are set in the claim's transaction. */
    static final String COMPLETE = """
            UPDATE idempotency_keys SET status = :status, content_type = :contentType, body = :body
            WHERE idempotency_key = :key AND status IS NULL
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    IdempotencyStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Claimed → write → store → Response. Not claimed → the stored Response, or Invalid (see
     * {@link Keyed.Invalid}). The request hash is SHA-256 of the operation, skuId and canonical request (Y3). Opens a
     * READ COMMITTED transaction, or joins a READ COMMITTED or DEFAULT caller's transaction and refuses any other
     * isolation (IllegalStateException, before any I/O); when joined, the caller's now() (T1) and rollback scope apply.
     * A runtime exception from the write escapes unchanged and rolls the claim back; when joined, it marks the caller's
     * transaction rollback-only.
     */
    public Keyed run(UUID key, Operation operation, String skuId, String canonicalRequest,
            Supplier<StoredResponse> write) {
        // null: no transaction, or a caller at ISOLATION_DEFAULT, which is Postgres's READ COMMITTED here.
        Integer joined = TransactionSynchronizationManager.isActualTransactionActive()
                ? TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() : null;
        if (joined != null && joined != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new IllegalStateException("IdempotencyStore.run joins only a READ COMMITTED transaction");
        }
        byte[] hash = RequestHash.of(operation, skuId, canonicalRequest);
        return transaction.execute(status -> {
            if (!claim(key, operation, skuId, hash)) {
                return replay(key, operation, skuId, hash);
            }
            StoredResponse response = write.get();
            complete(key, response);
            return new Keyed.Response(response);
        });
    }

    private boolean claim(UUID key, Operation operation, String skuId, byte[] hash) {
        return jdbc.sql(CLAIM)
                .param("key", key)
                .param("operation", operation.dbValue())
                .param("skuId", skuId)
                .param("hash", hash)
                .query((rs, n) -> rs.getObject(1))
                .optional()
                .isPresent();
    }

    private Keyed replay(UUID key, Operation operation, String skuId, byte[] hash) {
        return jdbc.sql(STORED)
                .param("key", key)
                .param("validitySeconds", KEY_VALIDITY.toSeconds())
                .<Keyed>query((rs, n) -> {
                    boolean sameRequest = rs.getString("operation").equals(operation.dbValue())
                            && rs.getString("sku_id").equals(skuId)
                            && MessageDigest.isEqual(rs.getBytes("request_hash"), hash);
                    Integer status = rs.getObject("status", Integer.class);
                    // A committed row without a stored response is never replayed: the key is used up (A18).
                    if (rs.getBoolean("expired") || !sameRequest || status == null) {
                        return new Keyed.Invalid();
                    }
                    return new Keyed.Response(
                            new StoredResponse(status, rs.getString("content_type"), rs.getString("body")));
                })
                .optional()
                .orElseThrow(() -> new IllegalStateException("Idempotency-Key " + key + " neither claimed nor stored"));
    }

    private void complete(UUID key, StoredResponse response) {
        int updated = jdbc.sql(COMPLETE)
                .param("key", key)
                .param("status", response.status())
                .param("contentType", response.contentType())
                .param("body", response.body())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency-Key " + key + " was not completed");
        }
    }
}
