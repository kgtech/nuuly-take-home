package com.kgtech.inventoryapi.idempotency;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a keyed write once (R2, G14, DESIGN-V2 §2, A33): claims the Idempotency-Key, runs the write and stores its
 * response in one READ COMMITTED transaction, joining the caller's when there is one. A concurrent claim of the same
 * key blocks on the primary key until the first transaction ends, then finds the committed row and replays it. A
 * {@code @Component}, not a {@code @Repository}: persistence exception translation would turn the write's own
 * exceptions into InvalidDataAccessApiUsageException.
 */
@Component
public class IdempotencyStore {

    /** A keyed write's answer: the stored response, first run or replay alike, or 400 "Invalid request". */
    public sealed interface Keyed {

        record Response(StoredResponse response) implements Keyed {
        }

        /** Different operation, skuId or request (S8), older than 24h (T1), or a cleared response (A18). */
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
     * {@link Keyed.Invalid}). The request hash is SHA-256 of the operation, skuId and canonical request (Y3). A
     * runtime exception from the write escapes unchanged and rolls the claim back.
     */
    public Keyed run(UUID key, Operation operation, String skuId, String canonicalRequest,
            Supplier<StoredResponse> write) {
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
                    // A committed row without a response was cleared by the README's retention clean-up: used up (A18).
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
