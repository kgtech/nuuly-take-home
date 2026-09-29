package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Y3: request_hash is SHA-256 of operation + "\n" + skuId + "\n" + canonical request, from the parsed request. The spec
 * POSTs pass the quantity's digits (A33), so the pinned bytes below are the ones stored rows were written with.
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

    /** A29, A33: the create hash covers the request's own canonical form, under its own operation. */
    @Test
    void createHashUsesTheCanonicalRequest() {
        assertThat(hex(RequestHash.of(Operation.CREATE, "widget", "5")))
                .isNotEqualTo(hex(RequestHash.of(Operation.ADD, "widget", "5")));
        assertThat(hex(RequestHash.of(Operation.CREATE, "widget", "a")))
                .isNotEqualTo(hex(RequestHash.of(Operation.CREATE, "widget", "b")));
        assertThat(Operation.CREATE.dbValue()).isEqualTo("create");
    }

    @Test
    void hashIs32Bytes() {
        assertThat(RequestHash.of(Operation.ADD, "widget", Integer.toString(Integer.MAX_VALUE))).hasSize(32);
        assertThat(RequestHash.of(Operation.CREATE, "widget", "x".repeat(40_000))).hasSize(32);
    }
}
