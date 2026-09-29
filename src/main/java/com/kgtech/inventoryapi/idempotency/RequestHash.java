package com.kgtech.inventoryapi.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of operation + "\n" + skuId + "\n" + canonical request, from the parsed request (Y3). For the spec POSTs the
 * canonical request is the quantity as decimal digits, so their hashes are the bytes they always were (A33). A /v2
 * request hashes "v2\n" first (H10), so its hash never equals an unversioned request's.
 */
final class RequestHash {

    private RequestHash() {
    }

    static byte[] of(Operation operation, String skuId, String canonicalRequest) {
        return sha256(operation.dbValue() + "\n" + skuId + "\n" + canonicalRequest);
    }

    static byte[] ofV2(Operation operation, String skuId, String canonicalRequest) {
        return sha256("v2\n" + operation.dbValue() + "\n" + skuId + "\n" + canonicalRequest);
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
