package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_BASE_PATH;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.server.PathContainer.Element;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.UriUtils;

/**
 * The one parser of the request path (F-04, L19, L21): the routed path under /inventory or /v2/inventory, as Spring
 * matches it. Segments are percent-decoded, the context path is removed and ";" content of the literal segments is
 * ignored, so an encoded prefix (/%69nventory) cannot slip past a check. The SKU segment is the exception: it keeps
 * its ";" content (C3), because Spring strips it before binding and would otherwise hand the service a different SKU.
 * This is the only class, with the error advice's log lines, that reads the raw request URI.
 *
 * @param v2 whether the path lies under /v2/inventory
 * @param kind the route shape
 * @param rawSkuId the decoded SKU segment with any ";" content, or null on the list route
 * @param skuHasMatrix whether the SKU segment contains a ";" (literal or %3B)
 */
record RoutedPath(boolean v2, RouteKind kind, String rawSkuId, boolean skuHasMatrix) {

    private static final String BASE_SEGMENT = BASE_PATH.substring(1);
    private static final String V2_SEGMENT = V2_BASE_PATH.substring(1, V2_BASE_PATH.indexOf('/', 1));
    private static final String ERROR_SEGMENT = "error";
    private static final String PURCHASE_SEGMENT = "purchase";
    private static final String DETAILS_SEGMENT = "details";

    /** The request's routed path; empty when it does not lie under /inventory or /v2/inventory. */
    static Optional<RoutedPath> of(HttpServletRequest request) {
        return parse(request.getRequestURI(), request.getContextPath());
    }

    /** Whether the request is for exactly Boot's error path, as Spring routes it: decoded, ";" content ignored (M-02). */
    static boolean isErrorPath(HttpServletRequest request) {
        List<PathSegment> segments = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication().elements().stream().filter(PathSegment.class::isInstance)
                .map(PathSegment.class::cast).toList();
        return segments.size() == 1 && ERROR_SEGMENT.equals(segments.getFirst().valueToMatch());
    }

    /** Empty when the path does not lie under /inventory or /v2/inventory. */
    static Optional<RoutedPath> parse(String requestUri, String contextPath) {
        List<PathSegment> segments = new ArrayList<>();
        for (Element element : RequestPath.parse(requestUri, contextPath).pathWithinApplication().elements()) {
            if (element instanceof PathSegment segment) {
                segments.add(segment);
            }
        }
        boolean v2 = !segments.isEmpty() && V2_SEGMENT.equals(segments.getFirst().valueToMatch());
        if (v2) {
            segments.removeFirst();
        }
        if (segments.isEmpty() || !BASE_SEGMENT.equals(segments.getFirst().valueToMatch())) {
            return Optional.empty();
        }
        if (segments.size() == 1) {
            return Optional.of(new RoutedPath(v2, RouteKind.LIST, null, false));
        }
        String skuId = decode(segments.get(1).value());
        RouteKind kind = kind(v2, segments);
        return Optional.of(new RoutedPath(v2, kind, skuId, skuId.indexOf(';') >= 0));
    }

    private static RouteKind kind(boolean v2, List<PathSegment> segments) {
        if (segments.size() == 2) {
            return RouteKind.ITEM;
        }
        if (segments.size() == 3) {
            String last = segments.get(2).valueToMatch();
            if (PURCHASE_SEGMENT.equals(last)) {
                return RouteKind.PURCHASE;
            }
            if (v2 && DETAILS_SEGMENT.equals(last)) {
                return RouteKind.DETAILS;
            }
        }
        return RouteKind.OTHER;
    }

    /** Tomcat has already rejected a malformed escape; if one reaches here, keep the text as sent. */
    private static String decode(String segment) {
        try {
            return UriUtils.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
