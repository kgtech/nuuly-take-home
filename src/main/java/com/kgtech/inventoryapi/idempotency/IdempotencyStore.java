package com.kgtech.inventoryapi.idempotency;

import java.security.MessageDigest;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Claims, completes and replays Idempotency-Keys inside the caller's SERIALIZABLE transaction (R2, G14, X1).
 * A {@code @Component}, not a {@code @Repository}: persistence exception translation would turn the documented
 * IllegalStateException (and any exception from the action) into InvalidDataAccessApiUsageException. JdbcClient
 * already translates SQL errors, so 40001 still reaches the retry as a PessimisticLockingFailureException.
 */
@Component
public class IdempotencyStore {

    /** R2: claim the key; no row back means it already exists. */
    static final String CLAIM = """
            INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash)
            VALUES (:key, :operation, :skuId, :hash)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING idempotency_key
            """;

    /** T1: expiry uses the database clock (transaction start time). */
    static final String STORED = """
            SELECT operation, sku_id, request_hash, status, content_type, body,
                   created_at < now() - interval '24 hours' AS expired
            FROM idempotency_keys WHERE idempotency_key = :key
            """;

    /** Y4: the response columns are set in the claim's transaction. */
    static final String COMPLETE = """
            UPDATE idempotency_keys SET status = :status, content_type = :contentType, body = :body
            WHERE idempotency_key = :key AND status IS NULL
            """;

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must run inside an active transaction, else IllegalStateException. Claim → action → store → Executed. No claim
     * row: expired → Rejected; different operation, skuId or hash → Rejected; else Replayed. A missing or incomplete
     * stored row → IllegalStateException.
     */
    public KeyedResult execute(IdempotentRequest request, Supplier<StoredResponse> action) {
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
            throw new IllegalStateException("Idempotency-Key " + request.key() + " has no stored response");
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
