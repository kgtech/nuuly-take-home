package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Y3: request_hash is SHA-256 of operation + "\n" + skuId + "\n" + quantity from the parsed request. */
class RequestHashTest {

    private static String hex(byte[] hash) {
        return HexFormat.of().formatHex(hash);
    }

    /** Expected values: printf 'add\nwidget\n5' | shasum -a 256 (and the same for purchase). */
    @Test
    void hashIsSha256OfOperationSkuIdQuantity() {
        assertThat(hex(RequestHash.of(Operation.ADD, "widget", 5)))
                .isEqualTo("24366c5cd0cd15ed3250e59c23b11c984820899122c7d58e6b42601f9d4b1246");
        assertThat(hex(RequestHash.of(Operation.PURCHASE, "widget", 5)))
                .isEqualTo("c6daae4a6ef7c8753c9055bafd737330257f5b1da55691eb1a3056f29ec1fffd");
    }

    @Test
    void eachFieldChangesHash() {
        String base = hex(RequestHash.of(Operation.ADD, "ABC", 5));

        assertThat(hex(RequestHash.of(Operation.ADD, "ABC", 5))).as("deterministic").isEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.PURCHASE, "ABC", 5))).as("operation").isNotEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.ADD, "abc", 5))).as("skuId case (G1)").isNotEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.ADD, "ABD", 5))).as("skuId").isNotEqualTo(base);
        assertThat(hex(RequestHash.of(Operation.ADD, "ABC", 6))).as("quantity").isNotEqualTo(base);
    }

    @Test
    void hashIs32Bytes() {
        assertThat(RequestHash.of(Operation.ADD, "widget", Integer.MAX_VALUE)).hasSize(32);
        assertThat(new IdempotentRequest(UUID.randomUUID(), Operation.PURCHASE, "widget", 1).requestHash())
                .hasSize(32)
                .isEqualTo(RequestHash.of(Operation.PURCHASE, "widget", 1));
    }
}
