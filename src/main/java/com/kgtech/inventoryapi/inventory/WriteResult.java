package com.kgtech.inventoryapi.inventory;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/** What a keyed write returns: a stock or details outcome, a stored keyed response, or an invalid request (R1, Z1). */
public sealed interface WriteResult permits StockOutcome, DetailsOutcome, WriteResult.Stored, WriteResult.InvalidRequest {

    /** A first or replayed keyed response, sent unchanged (Y4). */
    record Stored(StoredResponse response) implements WriteResult {
    }

    /** 400 "Invalid request": malformed skuId on create (spec or v2), malformed or reused Idempotency-Key; never stored. */
    record InvalidRequest() implements WriteResult {
    }
}
