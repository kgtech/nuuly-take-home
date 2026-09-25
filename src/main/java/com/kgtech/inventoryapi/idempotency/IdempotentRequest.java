package com.kgtech.inventoryapi.idempotency;

import java.util.Objects;
import java.util.UUID;

/** A keyed stock write: the key plus the parsed, validated request it guards (S8, Y3). */
public record IdempotentRequest(UUID key, Operation operation, String skuId, int quantity) {

    public IdempotentRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(skuId, "skuId");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
    }

    /** SHA-256 of the parsed request (Y3). */
    public byte[] requestHash() {
        return RequestHash.of(operation, skuId, quantity);
    }
}
