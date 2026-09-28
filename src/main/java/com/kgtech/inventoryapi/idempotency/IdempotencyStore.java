package com.kgtech.inventoryapi.idempotency;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Claims, completes and replays Idempotency-Keys inside the caller's READ COMMITTED transaction (R2, G14, DESIGN-V2
 * §2). A concurrent claim of the same key blocks on the primary key until the first transaction ends, then finds the
 * committed row and replays it. A {@code @Component}, not a {@code @Repository}: persistence exception translation
 * would turn the documented IllegalStateException (and any exception from the action) into
 * InvalidDataAccessApiUsageException.
 */
@Component
class IdempotencyStore {

    /** R2: claim the key; no row back means it already exists. */
    static final String CLAIM = """
            INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash)
            VALUES (:key, :operation, :skuId, :hash)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING idempotency_key
            """;

    /** T1: how long a key stays valid, by the database clock. */
    public static final Duration KEY_VALIDITY = Duration.ofHours(24);

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

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must run inside an active transaction, else IllegalStateException. Claim → action → store → Executed. No claim
     * row: expired → Rejected; different operation, skuId or hash → Rejected; else Replayed. A missing or incomplete
     * stored row → IllegalStateException.
     */
    KeyedResult execute(IdempotentRequest request, Supplier<StoredResponse> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IdempotencyStore.execute needs an active transaction");
        }
        byte[] hash = request.requestHash();
        if (claim(request, hash)) {
            StoredResponse response = action.get();
            complete(request, response);
            return new KeyedResult.Executed(response);
        }
        StoredRow row = stored(request).orElseThrow(
                () -> new IllegalStateException("Idempotency-Key " + request.key() + " neither claimed nor stored"));
        if (row.expired()
                || !row.operation().equals(request.operation().dbValue())
                || !row.skuId().equals(request.skuId())
                || !MessageDigest.isEqual(row.requestHash(), hash)) {
            return new KeyedResult.Rejected();
        }
        StoredResponse response = row.response();
        if (response == null) {
            // A committed row without a response is a tombstone (the README's clean-up ran early): the key is used up.
            return new KeyedResult.Rejected();
        }
        return new KeyedResult.Replayed(response);
    }

    private boolean claim(IdempotentRequest request, byte[] hash) {
        return jdbc.sql(CLAIM)
                .param("key", request.key())
                .param("operation", request.operation().dbValue())
                .param("skuId", request.skuId())
                .param("hash", hash)
                .query((rs, n) -> rs.getObject(1))
                .optional()
                .isPresent();
    }

    private Optional<StoredRow> stored(IdempotentRequest request) {
        return jdbc.sql(STORED)
                .param("key", request.key())
                .param("validitySeconds", KEY_VALIDITY.toSeconds())
                .query((rs, n) -> new StoredRow(
                        rs.getString("operation"),
                        rs.getString("sku_id"),
                        rs.getBytes("request_hash"),
                        rs.getObject("status", Integer.class),
                        rs.getString("content_type"),
                        rs.getString("body"),
                        rs.getBoolean("expired")))
                .optional();
    }

    private void complete(IdempotentRequest request, StoredResponse response) {
        int updated = jdbc.sql(COMPLETE)
                .param("key", request.key())
                .param("status", response.status())
                .param("contentType", response.contentType())
                .param("body", response.body())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency-Key " + request.key() + " was not completed");
        }
    }
}
