package com.kgtech.inventoryapi.inventory;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * What a write returns (R1, A33, A38): the outcome of its own family {@code O} (StockOutcome.Add for an add,
 * StockOutcome.Purchase for a purchase), a stored keyed response, or an invalid request. A caller's switch over one
 * write's result is exhaustive over that write's outcomes only.
 */
public sealed interface WriteResult<O> {

    /** The write's outcome, unkeyed; also purchase's 404 for a malformed skuId (G11). Rendered like a stored one. */
    record Done<O>(O outcome) implements WriteResult<O> {
    }

    /** A first or replayed keyed response, sent unchanged (Y4). */
    record Stored<O>(StoredResponse response) implements WriteResult<O> {
    }

    /** 400 "Invalid request": a malformed skuId on create, a malformed or reused Idempotency-Key; never stored. */
    record InvalidRequest<O>() implements WriteResult<O> {
    }
}
