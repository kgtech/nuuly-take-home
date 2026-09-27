package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

/** S3: a present Idempotency-Key must be a hyphenated UUID; anything else is 400 before any database work. */
class IdempotencyKeyTest {

    private static final String UUID_TEXT = "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b";

    static Stream<String> validKeys() {
        return Stream.of(UUID_TEXT, UUID_TEXT.toUpperCase(), "3F2b8C1e-9A4d-4E7f-B6a0-1C2d3E4f5A6b",
                "00000000-0000-0000-0000-000000000000");
    }

    static Stream<String> invalidKeys() {
        return Stream.of("", " ", "not-a-uuid",
                "1-1-1-1-1", // UUID.fromString accepts it
                UUID_TEXT.replace("-", ""),
                "{" + UUID_TEXT + "}",
                " " + UUID_TEXT,
                UUID_TEXT + " ",
                UUID_TEXT + "0",
                UUID_TEXT + "\n",
                UUID_TEXT.substring(1),
                UUID_TEXT.replace('b', 'g'),
                UUID_TEXT + "," + UUID_TEXT);
    }

    @ParameterizedTest
    @MethodSource("validKeys")
    void validKeysAccepted(String key) {
        assertThat(IdempotencyKey.isValid(key)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("invalidKeys")
    void invalidKeysRejected(String key) {
        assertThat(IdempotencyKey.isValid(key)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("validKeys")
    void parseReturnsSameUuidRegardlessOfCase(String key) {
        assertThat(IdempotencyKey.parse(key)).isEqualTo(UUID.fromString(key));
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("invalidKeys")
    void parseRejectsInvalid(String key) {
        assertThatThrownBy(() -> IdempotencyKey.parse(key)).isInstanceOf(IllegalArgumentException.class);
    }
}
