package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Y3, H10: request_hash is SHA-256 of operation + "\n" + skuId + "\n" + canonical request, from the parsed request; a
 * /v2 request prefixes "v2\n". The spec POSTs pass the quantity's digits (A33), so the pinned bytes below are the ones
 * stored rows were written with.
 */
class RequestHashTest {

    private static String hex(byte[] hash) {
        return HexFormat.of().formatHex(hash);
    }

    /** Expected values: printf 'add\nwidget\n5' | shasum -a 256 (and the same for purchase). */
    @Test
    void hashIsSha256OfOperationSkuIdQuantity() {
        assertThat(hex(RequestHash.of(Operation.ADD, "widget", "5")))
                .isEqualTo("24366c5cd0cd15ed3250e59c23b11c984820899122c7d58e6b42601f9d4b1246");
        assertThat(hex(RequestHash.of(Operation.PURCHASE, "widget", "5")))
                .isEqualTo("c6daae4a6ef7c8753c9055bafd737330257f5b1da55691eb1a3056f29ec1fffd");
    }

    @Test
    void eachFieldChangesHash() {
        String base = hex(RequestHash.of(Operation.ADD, "ABC", "5"));

        assertThat(hex(RequestHash.of(Operation.ADD, "ABC", "5"))).as("deterministic").isEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.PURCHASE, "ABC", "5"))).as("operation").isNotEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.ADD, "abc", "5"))).as("skuId case (G1)").isNotEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.ADD, "ABD", "5"))).as("skuId").isNotEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.ADD, "ABC", "6"))).as("quantity").isNotEqualTo(base);
    }

    /**
     * H10: a /v2 request's hash also covers the API version, so a key stored by an unversioned request never matches
     * the same request on /v2.
     */
    @Test
    void v2HashPrefixesTheApiVersion() {
        assertThat(hex(RequestHash.ofV2(Operation.ADD, "widget", "5")))
                .isEqualTo(sha256Hex("v2\nadd\nwidget\n5"));
        assertThat(hex(RequestHash.ofV2(Operation.PURCHASE, "widget", "5")))
                .isEqualTo(sha256Hex("v2\npurchase\nwidget\n5"));
        assertThat(hex(RequestHash.ofV2(Operation.ADD, "widget", "5")))
                .as("never the unversioned bytes")
                .isNotEqualTo(hex(RequestHash.of(Operation.ADD, "widget", "5")));
        assertThat(hex(RequestHash.ofV2(Operation.ADD, "widget", "5")))
                .isNotEqualTo(hex(RequestHash.ofV2(Operation.ADD, "widget", "6")))
                .isNotEqualTo(hex(RequestHash.ofV2(Operation.PURCHASE, "widget", "5")))
                .isNotEqualTo(hex(RequestHash.ofV2(Operation.ADD, "Widget", "5")));
    }

    @Test
    void hashIs32Bytes() {
        assertThat(RequestHash.of(Operation.ADD, "widget", Integer.toString(Integer.MAX_VALUE))).hasSize(32);
        assertThat(RequestHash.ofV2(Operation.PURCHASE, "widget", Integer.toString(Integer.MAX_VALUE))).hasSize(32);
    }

    private static String sha256Hex(String text) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
