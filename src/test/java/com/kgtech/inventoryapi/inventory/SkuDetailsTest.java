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
        // A newline inside a value cannot collide with the field separator: the form is length-prefixed.
        assertThat(new CreateSku(new SkuDetails("a\nb", "c", Optional.empty(), List.of()), 1).fingerprint())
                .isNotEqualTo(new CreateSku(new SkuDetails("a", "b\nc", Optional.empty(), List.of()), 1).fingerprint());
    }
}
