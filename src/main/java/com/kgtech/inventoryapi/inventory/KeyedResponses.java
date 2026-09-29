package com.kgtech.inventoryapi.inventory;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * Renders a stock write's outcome as the response stored against its Idempotency-Key (Y4, A33, A38). The web layer
 * implements it with the same rendering an unkeyed answer gets, so the domain names no HTTP type (Z2). Stub for #87's
 * red tests; nothing implements or calls it yet.
 */
public interface KeyedResponses {

    /** A stock write's outcome: add or purchase (R1, U1). */
    StoredResponse toStored(String skuId, StockOutcome outcome);
}
