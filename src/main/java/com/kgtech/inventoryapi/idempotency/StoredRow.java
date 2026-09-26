package com.kgtech.inventoryapi.idempotency;

/** A row read back from idempotency_keys; expired is computed by Postgres (T1). */
record StoredRow(String operation, String skuId, byte[] requestHash, Integer status, String contentType, String body,
        boolean expired) {

    /** The stored response, or null while the claim's transaction has not completed it (never committed, Y4). */
    StoredResponse response() {
        return status == null ? null : new StoredResponse(status, contentType, body);
    }
}
