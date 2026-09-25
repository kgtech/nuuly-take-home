package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** G11, R7, S2: the skuId pattern ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$, matched whole. Plain unit test. */
class SkuIdTest {

    static Stream<String> validIds() {
        return Stream.of("a", "A", "0", "CW-XYCS-BM-01", "widget", "a.b_c-d", "a".repeat(64));
    }

    static Stream<String> invalidIds() {
        return Stream.of("", "-a", ".a", "_a", "a".repeat(65), "a b", "a/b", "abc\n", "é", "a!");
    }

    @ParameterizedTest
    @MethodSource
    void validIds(String skuId) {
        assertThat(SkuId.isValid(skuId)).isTrue();
    }

    @ParameterizedTest
    @MethodSource
    void invalidIds(String skuId) {
        assertThat(SkuId.isValid(skuId)).isFalse();
    }

    @Test
    void nullIsInvalid() {
        assertThat(SkuId.isValid(null)).isFalse();
    }
}
