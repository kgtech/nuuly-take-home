package com.kgtech.inventoryapi.idempotency;

import java.util.Optional;

/** How an {@link Idempotent} method's result type is checked, stored and rebuilt (Z1, S2, S3, Y4). */
public interface IdempotentResults<R> {

    /** Runs after the key check and before any transaction (U3); a present result is returned as-is and not stored. */
    Optional<R> beforeClaim(Operation operation, String skuId);

    /** The response stored against the key; runs inside the claim's transaction (Y4). */
    StoredResponse toStored(String skuId, R result);

    /** The result for a first keyed response and for a replay. */
    R stored(StoredResponse response);

    /** A malformed key (S3), or a reused key with another request or older than 24h (S8, T1); never stored. */
    R invalidRequest();
}
