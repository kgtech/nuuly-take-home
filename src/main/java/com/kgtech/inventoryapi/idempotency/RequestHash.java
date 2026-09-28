package com.kgtech.inventoryapi.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of operation + "\n" + skuId + "\n" + canonical body, from the parsed request (Y3). For the spec POSTs the
 * canonical body is the quantity as decimal digits, so their hashes are the bytes they always were (A29).
 */
final class RequestHash {

    private RequestHash() {
    }

    static byte[] of(Operation operation, String skuId, int quantity) {
        return of(operation, skuId, Integer.toString(quantity));
    }

    static byte[] of(Operation operation, String skuId, String canonicalBody) {
        String canonical = operation.dbValue() + "\n" + skuId + "\n" + canonicalBody;
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
