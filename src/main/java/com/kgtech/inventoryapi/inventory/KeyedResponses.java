package com.kgtech.inventoryapi.inventory;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * Renders a write's outcome as the response stored against its Idempotency-Key (Y4, A33). The web layer implements
 * it with the same rendering an unkeyed answer gets, so the domain names no HTTP type (Z2).
 */
public interface KeyedResponses {

    /** Runs inside the claim's transaction; {@code result} is a stock or details outcome. */
    StoredResponse toStored(String skuId, WriteResult result);
}
