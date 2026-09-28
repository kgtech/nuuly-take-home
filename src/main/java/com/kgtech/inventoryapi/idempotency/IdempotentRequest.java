package com.kgtech.inventoryapi.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * A keyed write: the key plus the parsed, validated request it guards (S8, Y3). {@code canonicalBody} is the
 * quantity as decimal digits for the spec POSTs (the v1 hash bytes are unchanged) and the request's
 * {@link Fingerprinted#fingerprint()} otherwise (A29).
 */
record IdempotentRequest(UUID key, Operation operation, String skuId, String canonicalBody) {

    IdempotentRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(canonicalBody, "canonicalBody");
    }

    /** A spec stock write: operation, skuId and a quantity of at least 1. */
    static IdempotentRequest of(UUID key, Operation operation, String skuId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
        return new IdempotentRequest(key, operation, skuId, Integer.toString(quantity));
    }

    /** SHA-256 of the parsed request (Y3). */
    byte[] requestHash() {
        return RequestHash.of(operation, skuId, canonicalBody);
    }
}
