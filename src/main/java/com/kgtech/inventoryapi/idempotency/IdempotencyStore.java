package com.kgtech.inventoryapi.idempotency;

import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Claims, completes and replays Idempotency-Keys inside the caller's SERIALIZABLE transaction (R2, G14, X1). */
@Repository
public class IdempotencyStore {

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
        throw new UnsupportedOperationException("not implemented");
    }
}
