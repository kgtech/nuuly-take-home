package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** DESIGN-V2 §8 "Representation": the domain records enforce the field rules, so no path can bypass them. */
class SkuDetailsTest {

    private static final String URL = "https://cdn.example.com/a.jpg";

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("empty name", (Runnable) () -> new SkuDetails("", "", Optional.empty(), List.of())),
                Arguments.of("blank name", (Runnable) () -> new SkuDetails(" \t", "", Optional.empty(), List.of())),
                Arguments.of("121-char name", (Runnable) () -> new SkuDetails("n".repeat(121), "", Optional.empty(),
                        List.of())),
                Arguments.of("2001-char description", (Runnable) () -> new SkuDetails("n", "d".repeat(2001),
                        Optional.empty(), List.of())),
                Arguments.of("11 images", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        java.util.Collections.nCopies(11, URL))),
                Arguments.of("relative url", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("/a.jpg"))),
                Arguments.of("ftp url", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("ftp://x/a.jpg"))),
                Arguments.of("no host", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("https:///a.jpg"))),
                Arguments.of("space in url", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("https://x/a b.jpg"))),
                Arguments.of("2049-char url", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("https://x/" + "a".repeat(2039)))),
                Arguments.of("newline in url", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("https://x/a\nb.jpg"))),
                Arguments.of("non-ascii url", (Runnable) () -> new SkuDetails("n", "", Optional.empty(),
                        List.of("https://x/\u00fc.jpg"))),
                Arguments.of("NUL in name", (Runnable) () -> new SkuDetails("a\u0000b", "", Optional.empty(), List.of())),
                Arguments.of("newline in name", (Runnable) () -> new SkuDetails("a\nb", "", Optional.empty(), List.of())),
                Arguments.of("DEL in description", (Runnable) () -> new SkuDetails("n", "a\u007fb", Optional.empty(),
                        List.of())),
                Arguments.of("lone high surrogate", (Runnable) () -> new SkuDetails("a\ud800", "", Optional.empty(),
                        List.of())),
                Arguments.of("lone low surrogate", (Runnable) () -> new SkuDetails("n", "\udc00x", Optional.empty(),
                        List.of())),
                Arguments.of("negative amount", (Runnable) () -> new SkuCost(-1, "USD")),
                Arguments.of("lowercase currency", (Runnable) () -> new SkuCost(1, "usd")),
                Arguments.of("two-letter currency", (Runnable) () -> new SkuCost(1, "US")),
                Arguments.of("negative initial quantity", (Runnable) () -> new CreateSku(
                        new SkuDetails("n", "", Optional.empty(), List.of()), -1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejects(String what, Runnable construct) {
        assertThatThrownBy(construct::run).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheBoundaries() {
        SkuDetails details = new SkuDetails("n".repeat(120), "d".repeat(2000), Optional.of(new SkuCost(0, "EUR")),
                java.util.Collections.nCopies(10, "https://x/" + "a".repeat(2038)));
        assertThat(details.images()).hasSize(10);
        assertThat(details.images().getFirst()).hasSize(2048);
        assertThat(new CreateSku(details, 0).initialQuantity()).isZero();
        assertThat(new CreateSku(details, Integer.MAX_VALUE).initialQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(new SkuDetails("n", "", Optional.empty(), List.of()).description()).isEmpty();
        assertThat(new SkuDetails("Caf\u00e9 \ud83d\ude00", "line\nnext\ttab", Optional.empty(), List.of()).name())
                .isEqualTo("Caf\u00e9 \ud83d\ude00");
    }

    /** Y3 for creates: the canonical form is built field by field; the same request always renders the same. */
    @Test
    void createFingerprintIsCanonicalAndCoversEveryField() {
        CreateSku base = new CreateSku(new SkuDetails("Shirt", "Long", Optional.of(new SkuCost(5, "USD")),
                List.of(URL)), 3);
        String fingerprint = base.fingerprint();

        assertThat(fingerprint).isEqualTo(new CreateSku(new SkuDetails("Shirt", "Long",
                Optional.of(new SkuCost(5, "USD")), List.of(URL)), 3).fingerprint());
        assertThat(new CreateSku(base.details(), 4).fingerprint()).as("initialQuantity").isNotEqualTo(fingerprint);
        assertThat(new CreateSku(new SkuDetails("Shirts", "Long", Optional.of(new SkuCost(5, "USD")), List.of(URL)),
                3).fingerprint()).as("name").isNotEqualTo(fingerprint);
        assertThat(new CreateSku(new SkuDetails("Shirt", "Longer", Optional.of(new SkuCost(5, "USD")), List.of(URL)),
                3).fingerprint()).as("description").isNotEqualTo(fingerprint);
        assertThat(new CreateSku(new SkuDetails("Shirt", "Long", Optional.of(new SkuCost(6, "USD")), List.of(URL)),
                3).fingerprint()).as("amount").isNotEqualTo(fingerprint);
        assertThat(new CreateSku(new SkuDetails("Shirt", "Long", Optional.of(new SkuCost(5, "EUR")), List.of(URL)),
                3).fingerprint()).as("currency").isNotEqualTo(fingerprint);
        assertThat(new CreateSku(new SkuDetails("Shirt", "Long", Optional.empty(), List.of(URL)), 3).fingerprint())
                .as("no cost").isNotEqualTo(fingerprint);
        assertThat(new CreateSku(new SkuDetails("Shirt", "Long", Optional.of(new SkuCost(5, "USD")), List.of()), 3)
                .fingerprint()).as("images").isNotEqualTo(fingerprint);
        // A separator-like value cannot collide with a field boundary: the form is length-prefixed.
        assertThat(new CreateSku(new SkuDetails("n", "1:a\n", Optional.empty(), List.of()), 1).fingerprint())
                .isNotEqualTo(new CreateSku(new SkuDetails("n", "a", Optional.empty(), List.of()), 1).fingerprint());
        assertThat(new CreateSku(new SkuDetails("n", "", Optional.empty(), List.of("https://x/a")), 1).fingerprint())
                .isNotEqualTo(new CreateSku(new SkuDetails("n", "12:https://x/a", Optional.empty(), List.of()), 1)
                        .fingerprint());
    }
}
