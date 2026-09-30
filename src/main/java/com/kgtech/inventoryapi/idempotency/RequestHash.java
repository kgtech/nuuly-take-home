package com.kgtech.inventoryapi.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of operation + "\n" + skuId + "\n" + canonical request, from the parsed request (Y3). The canonical request
 * of both POSTs is the quantity as decimal digits, so the hashes are the bytes they always were and keys stored before
 * A33 still replay.
 */
final class RequestHash {

    private RequestHash() {
    }

    static byte[] of(Operation operation, String skuId, String canonicalRequest) {
        String canonical = operation.dbValue() + "\n" + skuId + "\n" + canonicalRequest;
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
