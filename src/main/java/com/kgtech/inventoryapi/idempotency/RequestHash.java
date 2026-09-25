package com.kgtech.inventoryapi.idempotency;

/** SHA-256 of operation + "\n" + skuId + "\n" + quantity, from the parsed request (Y3). */
final class RequestHash {

    private RequestHash() {
    }

    static byte[] of(Operation operation, String skuId, int quantity) {
        throw new UnsupportedOperationException("not implemented");
    }
}
