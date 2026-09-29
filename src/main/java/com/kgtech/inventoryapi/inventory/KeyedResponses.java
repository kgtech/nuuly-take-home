package com.kgtech.inventoryapi.inventory;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * Renders a write's outcome as the response stored against its Idempotency-Key (Y4, A33), one typed method per
 * outcome family (A38). The web layer implements it with the same rendering an unkeyed answer gets, so the domain
 * names no HTTP type (Z2). Both run inside the claim's transaction.
 */
public interface KeyedResponses {

    /** A stock write's outcome: add or purchase (R1, U1). */
    StoredResponse toStored(String skuId, StockOutcome outcome);

    /** The v2 create's outcome (A28); the created item carries its own skuId. */
    StoredResponse toStored(String skuId, DetailsOutcome outcome);
}
