package com.kgtech.inventoryapi.inventory.web;

import static org.springframework.http.HttpHeaders.ACCEPT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request guard for every route with a SKU segment, under /inventory and /v2/inventory (issue #23, C-04, C-34,
 * F-04, decisions C3, H11, L21). It is driven by the route kind and the method, never by segment counts: a small table
 * (RULES below) maps each kind and method to its frozen answer, so a new route is guarded by giving its shape a
 * {@link RouteKind} and a row, and RouteGuardCoverageTest fails until it has both. The checks, in order:
 * <ol>
 * <li>a raw ';' (or %3B) in the SKU segment is answered as the malformed SKU ID it is, because Spring strips ";matrix"
 * content before binding and /inventory/ABC-1;lot=7 would otherwise reach SKU ABC-1 (G11: item write 400, item read
 * and purchase 404, details PUT 400);
 * <li>a write whose most specific Accept range matching JSON has q=0 is 400 (U2, Y1, RFC 9110 section 12.5.1), which
 * the produces condition alone accepts;
 * <li>a write with a Content-Length over its cap is 400 (A19);
 * <li>on every write, a chunked body is counted as it is read, with the same cap (R-01, M-01 thawed A19).
 * </ol>
 * The path is the routed one from {@link RoutedPath} (decoded segments, as Spring matches), so an encoded prefix cannot
 * bypass it. HEAD is guarded like GET (Spring serves HEAD through the GET handler). The filter runs before argument
 * resolution, hence before @Valid: a ';' SKU with a bad body gets the SKU answer (H-M13). Nothing is written.
 */
@Component
final class InventoryRequestGuardFilter extends OncePerRequestFilter {

    /** The largest JSON body a spec request needs, with room for whitespace and ignored fields (G13). */
    static final long MAX_BODY_BYTES = 4096;
    /**
     * DESIGN-V2 section 8: the largest contract-valid details body, with every non-ASCII character of the name and
     * description written as a six-byte \\uXXXX escape (12,720 bytes) plus ten 2,048-byte ASCII URLs and the structure,
     * is about 33.4 KB; 64 KB leaves room for whitespace and ignored properties (critique F-conc-01).
     */
    static final long MAX_V2_BODY_BYTES = 65_536;

    private static final Supplier<ResponseEntity<String>> INVALID = TextErrors::invalidRequest;
    private static final Supplier<ResponseEntity<String>> NOT_FOUND = TextErrors::skuNotFound;

    /**
     * What the guard does for one route: the answer to a ';' SKU, and, for a write, the body cap (a write also refuses
     * an Accept that excludes JSON). A read has cap 0 and is not a write.
     */
    private record Rule(Supplier<ResponseEntity<String>> matrixAnswer, boolean write, long cap) {

        static Rule read() {
            return new Rule(NOT_FOUND, false, 0);
        }
    }

    /** The rule for a route kind and method, or null when the guard has none (Spring answers: 404, 405, ...). */
    private static Rule rule(RoutedPath path, String method) {
        boolean get = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
        boolean post = HttpMethod.POST.matches(method);
        boolean put = HttpMethod.PUT.matches(method) && path.v2();
        return switch (path.kind()) {
            case ITEM -> get ? Rule.read() : post ? new Rule(INVALID, true, MAX_BODY_BYTES) : null;
            case PURCHASE -> post ? new Rule(NOT_FOUND, true, MAX_BODY_BYTES) : null;
            case DETAILS -> put ? new Rule(INVALID, true, MAX_V2_BODY_BYTES) : null;
            case LIST, OTHER -> null;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RoutedPath path = RoutedPath.of(request).orElse(null);
        Rule rule = path == null ? null : rule(path, request.getMethod());
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        if (path.skuHasMatrix()) {
            write(response, rule.matrixAnswer().get());
            return;
        }
        if (rule.write() && !acceptsJson(request)) {
            write(response, TextErrors.invalidRequest());
            return;
        }
        if (rule.write() && request.getContentLengthLong() > rule.cap()) {
            write(response, TextErrors.invalidRequest()); // larger than any body the contract describes
            return;
        }
        if (rule.write()) {
            // A chunked body has no Content-Length: count it as it is read (review R-01, M-01).
            chain.doFilter(new CappedBodyRequest(request, rule.cap()), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * RFC 9110 §12.5.1: the most specific range that matches application/json decides; q=0 refuses, and so does an Accept
     * with no range that matches it (the produces condition would refuse it too, but only on a route that exists; the
     * guard answers on every write route). No Accept accepts. All Accept header lines count. Unparseable Accept is left to
     * Spring.
     */
    private static boolean acceptsJson(HttpServletRequest request) {
        List<String> lines = Collections.list(request.getHeaders(ACCEPT));
        if (lines.isEmpty() || lines.stream().allMatch(String::isBlank)) {
            return true;
        }
        List<MediaType> types;
        try {
            types = new ArrayList<>(MediaType.parseMediaTypes(String.join(",", lines)));
        } catch (InvalidMediaTypeException e) {
            return true;
        }
        MediaType decisive = null;
        for (MediaType type : types) {
            if (type.isCompatibleWith(MediaType.APPLICATION_JSON)
                    && (decisive == null || specificity(type) > specificity(decisive))) {
                decisive = type;
            }
        }
        return decisive != null && decisive.getQualityValue() > 0;
    }

    /** 2 for a full type, 1 for a wildcard subtype, 0 for the full wildcard: the most specific range decides. */
    private static int specificity(MediaType type) {
        return (type.isWildcardType() ? 0 : 1) + (type.isWildcardSubtype() ? 0 : 1);
    }

    private static void write(HttpServletResponse response, ResponseEntity<String> error) throws IOException {
        TextErrors.write(response, error);
    }
}
