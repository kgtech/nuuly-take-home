package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * F-04, L19, L21: RoutedPath is the one place that parses the request path. The routed path is what Spring matches
 * on: percent-decoded segments, the context path removed, ";" matrix content of the literal segments ignored. Plain
 * unit test.
 */
class RoutedPathTest {

    private static RoutedPath parse(String uri) {
        return RoutedPath.parse(uri, "").orElseThrow(() -> new AssertionError(uri + " should be under an inventory base"));
    }

    static Stream<Arguments> kinds() {
        return Stream.of(
                Arguments.of("/inventory", false, RouteKind.LIST, null),
                Arguments.of("/v2/inventory", true, RouteKind.LIST, null),
                Arguments.of("/inventory/A", false, RouteKind.ITEM, "A"),
                Arguments.of("/v2/inventory/A", true, RouteKind.ITEM, "A"),
                Arguments.of("/inventory/A/purchase", false, RouteKind.PURCHASE, "A"),
                Arguments.of("/v2/inventory/A/purchase", true, RouteKind.PURCHASE, "A"),
                Arguments.of("/v2/inventory/A/details", true, RouteKind.DETAILS, "A"),
                // DETAILS exists only under /v2; any other shape under a base is OTHER
                Arguments.of("/inventory/A/details", false, RouteKind.OTHER, "A"),
                Arguments.of("/inventory/a/b/c", false, RouteKind.OTHER, "a"),
                Arguments.of("/v2/inventory/a/b/c", true, RouteKind.OTHER, "a"),
                Arguments.of("/inventory/A/purchase/x", false, RouteKind.OTHER, "A"),
                Arguments.of("/v2/inventory/A/other", true, RouteKind.OTHER, "A"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void classifiesEveryShapeUnderBothBases(String uri, boolean v2, RouteKind kind, String skuId) {
        RoutedPath path = parse(uri);

        assertThat(path.v2()).isEqualTo(v2);
        assertThat(path.kind()).isEqualTo(kind);
        assertThat(path.rawSkuId()).isEqualTo(skuId);
        assertThat(path.skuHasMatrix()).isFalse();
    }

    /** Spring ignores ";" content on a literal segment, so the route is the same and the SKU segment stays clean. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("literalSegmentMatrix")
    void matrixContentOfLiteralSegmentsIsIgnored(String uri, boolean v2, RouteKind kind) {
        RoutedPath path = parse(uri);

        assertThat(path.v2()).isEqualTo(v2);
        assertThat(path.kind()).isEqualTo(kind);
        assertThat(path.rawSkuId()).isEqualTo("A");
        assertThat(path.skuHasMatrix()).isFalse();
    }

    static Stream<Arguments> literalSegmentMatrix() {
        return Stream.of(
                Arguments.of("/inventory;v=1/A", false, RouteKind.ITEM),
                Arguments.of("/v2;x/inventory/A/purchase;y", true, RouteKind.PURCHASE),
                Arguments.of("/v2/inventory;a=b/A/details;c", true, RouteKind.DETAILS),
                Arguments.of("/inventory;v=1/A/purchase;x", false, RouteKind.PURCHASE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodedPrefixes")
    void encodedPrefixIsDecodedBeforeClassifying(String uri, boolean v2) {
        RoutedPath path = parse(uri);

        assertThat(path.v2()).isEqualTo(v2);
        assertThat(path.kind()).isEqualTo(RouteKind.ITEM);
        assertThat(path.rawSkuId()).isEqualTo("A");
    }

    static Stream<Arguments> encodedPrefixes() {
        return Stream.of(
                Arguments.of("/%69nventory/A", false),
                Arguments.of("/v%32/inventory/A", true),
                Arguments.of("/v2/%69nventory/A", true));
    }

    /** The SKU segment keeps its ";" content (C3): the guard must see what a client wrote, not what Spring binds. */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("matrixInTheSkuSegment")
    void semicolonInTheSkuSegmentIsKeptAndFlagged(String uri, String rawSkuId, RouteKind kind) {
        RoutedPath path = parse(uri);

        assertThat(path.kind()).isEqualTo(kind);
        assertThat(path.rawSkuId()).isEqualTo(rawSkuId);
        assertThat(path.skuHasMatrix()).isTrue();
    }

    static Stream<Arguments> matrixInTheSkuSegment() {
        return Stream.of(
                Arguments.of("/inventory/ABC-1;lot=7", "ABC-1;lot=7", RouteKind.ITEM),
                Arguments.of("/inventory/ABC-1%3Blot=7", "ABC-1;lot=7", RouteKind.ITEM),
                Arguments.of("/inventory/A%3bx", "A;x", RouteKind.ITEM),
                Arguments.of("/inventory/A;x/purchase", "A;x", RouteKind.PURCHASE),
                Arguments.of("/v2/inventory/A%3Bx/purchase", "A;x", RouteKind.PURCHASE),
                Arguments.of("/v2/inventory/A;x", "A;x", RouteKind.ITEM),
                Arguments.of("/v2/inventory/A;x/details", "A;x", RouteKind.DETAILS),
                Arguments.of("/v2/inventory/A%3Bx/details;y", "A;x", RouteKind.DETAILS),
                Arguments.of("/inventory;v=1/A;x/purchase", "A;x", RouteKind.PURCHASE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/inventory/A%2FB", "/v2/inventory/A%2fB"})
    void encodedSlashStaysInsideTheSkuSegment(String uri) {
        RoutedPath path = parse(uri);

        assertThat(path.kind()).isEqualTo(RouteKind.ITEM);
        assertThat(path.rawSkuId()).isEqualTo("A/B");
        assertThat(path.skuHasMatrix()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/app/inventory/A", "/app/inventory/A;x"})
    void contextPathIsRemoved(String uri) {
        RoutedPath path = RoutedPath.parse(uri, "/app").orElseThrow();

        assertThat(path.v2()).isFalse();
        assertThat(path.kind()).isEqualTo(RouteKind.ITEM);
        assertThat(path.skuHasMatrix()).isEqualTo(uri.endsWith(";x"));
    }

    @Test
    void contextPathAppliesToV2AndListToo() {
        assertThat(RoutedPath.parse("/app/v2/inventory/A/purchase", "/app").orElseThrow().kind())
                .isEqualTo(RouteKind.PURCHASE);
        assertThat(RoutedPath.parse("/app/inventory", "/app").orElseThrow().kind()).isEqualTo(RouteKind.LIST);
        // The context path itself is not a route: without the prefix removed this would not be under /inventory
        assertThat(RoutedPath.parse("/app/inventory/A", "")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/inventoryx", "/inventoryx/A", "/v2", "/v2/", "/v2/inventoryx", "/v2/other/A",
        "/v3/api-docs", "/swagger-ui/index.html", "/actuator/health", "/actuator/inventory/A", "/x/inventory/A"})
    void pathsOutsideTheInventoryBasesAreEmpty(String uri) {
        assertThat(RoutedPath.parse(uri, "")).isEmpty();
    }
}
