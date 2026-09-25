package com.kgtech.inventoryapi.idempotency;

/** Result of a keyed write: run now, replayed from the store, or rejected (S8, T1). */
public sealed interface KeyedResult {

    record Executed(StoredResponse response) implements KeyedResult {
    }

    record Replayed(StoredResponse response) implements KeyedResult {
    }

    /** Different operation, skuId or body, or a key older than 24h: 400 "Invalid request". */
    record Rejected() implements KeyedResult {
    }
}
