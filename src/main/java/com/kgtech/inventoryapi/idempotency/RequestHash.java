package com.kgtech.inventoryapi.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 of operation + "\n" + skuId + "\n" + quantity, from the parsed request (Y3). */
final class RequestHash {

    private RequestHash() {
    }

    static byte[] of(Operation operation, String skuId, int quantity) {
        String canonical = operation.dbValue() + "\n" + skuId + "\n" + quantity;
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
