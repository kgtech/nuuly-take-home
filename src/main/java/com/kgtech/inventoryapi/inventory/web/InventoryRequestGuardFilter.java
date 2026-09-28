package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_BASE_PATH;
import static org.springframework.http.HttpHeaders.ACCEPT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer.Element;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two request checks Spring MVC would get wrong on the spec's operations (issue #23, C-04, C-34, decision C3):
 * a raw ';' in the SKU segment (Spring strips ";matrix" content before binding, so /inventory/ABC-1;lot=7 would reach
 * SKU ABC-1) is answered as the malformed SKU ID it is (G11: create 400, GET and purchase 404); and a POST whose most
 * specific Accept range matching JSON has q=0 is 400 (U2, Y1, RFC 9110 §12.5.1), which the produces condition alone
 * accepts. The path is the routed one (decoded segments, as Spring matches), so an encoded prefix cannot bypass it.
 * HEAD is guarded like GET (Spring serves HEAD through the GET handler). The v2 paths (DESIGN-V2 §8) get the same
 * checks, with PUT treated as a write and a larger body cap. Nothing is written.
 */
@Component
final class InventoryRequestGuardFilter extends OncePerRequestFilter {

    private static final String BASE_SEGMENT = BASE_PATH.substring(1);
    private static final String V2_SEGMENT = V2_BASE_PATH.substring(1, V2_BASE_PATH.indexOf('/', 1));
    private static final String PURCHASE_SEGMENT = "purchase";
    /** The largest JSON body a spec request needs, with room for whitespace and ignored fields (G13). */
    static final long MAX_BODY_BYTES = 4096;
    /** DESIGN-V2 §8: a 2,000-character description plus ten 2,048-character URLs fits with room to spare. */
    static final long MAX_V2_BODY_BYTES = 32_768;

    /** The routed segments from "inventory" on, and whether they came under /v2. */
    private record Routed(List<PathSegment> segments, boolean v2) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return routed(request).segments().isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Routed routed = routed(request);
        List<PathSegment> segments = routed.segments();
        boolean get = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        boolean write = HttpMethod.POST.matches(request.getMethod())
                || routed.v2() && HttpMethod.PUT.matches(request.getMethod());
        boolean single = segments.size() == 2;
        boolean purchase = !routed.v2() && segments.size() == 3
                && PURCHASE_SEGMENT.equals(segments.get(2).valueToMatch());
        if (segments.size() >= 2 && (get && single || write && (single || purchase))) {
            PathSegment sku = segments.get(1);
            if (sku.value().indexOf(';') >= 0 || !sku.parameters().isEmpty()) {
                write(response, write && single ? TextErrors.invalidRequest() : TextErrors.skuNotFound());
                return;
            }
            if (write && !acceptsJson(request)) {
                write(response, TextErrors.invalidRequest());
                return;
            }
            long cap = routed.v2() ? MAX_V2_BODY_BYTES : MAX_BODY_BYTES;
            if (write && request.getContentLengthLong() > cap) {
                write(response, TextErrors.invalidRequest()); // larger than any body the contract describes
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * The routed path's segments (decoded, as Spring matches them) from "inventory" on, when the path lies under
     * /inventory/ or /v2/inventory/; else empty.
     */
    private static Routed routed(HttpServletRequest request) {
        List<PathSegment> segments = new ArrayList<>();
        for (Element element
                : RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication().elements()) {
            if (element instanceof PathSegment segment) {
                segments.add(segment);
            }
        }
        boolean v2 = !segments.isEmpty() && V2_SEGMENT.equals(segments.getFirst().valueToMatch());
        if (v2) {
            segments.removeFirst();
        }
        if (segments.size() < 2 || !BASE_SEGMENT.equals(segments.getFirst().valueToMatch())) {
            return new Routed(List.of(), v2);
        }
        return new Routed(segments, v2);
    }

    /**
     * RFC 9110 §12.5.1: the most specific range that matches application/json decides; q=0 refuses. No Accept, or no
     * matching range, accepts (Spring then negotiates). All Accept header lines count. Unparseable Accept is left to
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
        return decisive == null || decisive.getQualityValue() > 0;
    }

    /** 2 for a full type, 1 for a wildcard subtype, 0 for the full wildcard: the most specific range decides. */
    private static int specificity(MediaType type) {
        return (type.isWildcardType() ? 0 : 1) + (type.isWildcardSubtype() ? 0 : 1);
    }

    private static void write(HttpServletResponse response, ResponseEntity<String> error) throws IOException {
        response.setStatus(error.getStatusCode().value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.valueOf(error.getBody()));
        response.flushBuffer();
    }
}
