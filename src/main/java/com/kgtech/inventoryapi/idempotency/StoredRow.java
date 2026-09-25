package com.kgtech.inventoryapi.idempotency;

/** A row read back from idempotency_keys; expired is computed by Postgres (T1). */
record StoredRow(String operation, String skuId, byte[] requestHash, Integer status, String contentType, String body,
        boolean expired) {
}
