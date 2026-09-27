package com.kgtech.inventoryapi.idempotency;

import java.time.Instant;

/** Result of a keyed write: run now, replayed from the store, or rejected (S8, T1). */
sealed interface KeyedResult {

    /** Run now; {@code createdAt} is the claim's time (the database clock), the start of T1's 24 hours. */
    record Executed(StoredResponse response, Instant createdAt) implements KeyedResult {
    }

    /** Read back from the store; {@code createdAt} is the row's created_at. */
    record Replayed(StoredResponse response, Instant createdAt) implements KeyedResult {
    }

    /** Different operation, skuId or body, or a key older than 24h: 400 "Invalid request". */
    record Rejected() implements KeyedResult {
    }
}
