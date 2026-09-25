package com.kgtech.inventoryapi.inventory;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/** What a stock write returns: a stock outcome, a stored keyed response, or an invalid request (R1, Z1). */
sealed interface WriteResult permits StockOutcome, WriteResult.Stored, WriteResult.InvalidRequest {

    /** A first or replayed keyed response, sent unchanged (Y4). */
    record Stored(StoredResponse response) implements WriteResult {
    }

    /** 400 "Invalid request": malformed skuId on create, malformed or reused Idempotency-Key; never stored. */
    record InvalidRequest() implements WriteResult {
    }
}
