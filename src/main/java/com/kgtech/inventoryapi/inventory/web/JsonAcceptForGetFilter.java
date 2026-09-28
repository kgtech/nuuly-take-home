package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_BASE_PATH;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.IF_MODIFIED_SINCE;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer.Element;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * GET /inventory/** and GET /v2/inventory/** ignore the Accept header: it is presented as application/json (U2). The
 * v2 reads also hide If-None-Match and If-Modified-Since: their ETag tracks the details version only while the body
 * carries a quantity that changes with every purchase, so Spring's automatic 304 for a matching tag would let a
 * client keep a stale count (DESIGN-V2 §8; found by the front end's reload after a purchase).
 */
@Component
final class JsonAcceptForGetFilter extends OncePerRequestFilter {

    /** Only GET under /inventory and /v2/inventory; springdoc, actuator and every write are untouched (S6). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String path = routedPath(request);
        return !(under(path, BASE_PATH) || under(path, V2_BASE_PATH));
    }

    private static boolean under(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }

    /** requestURI minus contextPath, decoded and without ";" parameters: the path Spring matches handlers on. */
    private static String routedPath(HttpServletRequest request) {
        StringBuilder path = new StringBuilder();
        for (Element element
                : RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication().elements()) {
            path.append(element instanceof PathSegment segment ? segment.valueToMatch() : element.value());
        }
        return path.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new JsonAccept(request), response);
    }

    private static final class JsonAccept extends HttpServletRequestWrapper {

        JsonAccept(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if (isConditional(name)) {
                return null;
            }
            return isAccept(name) ? APPLICATION_JSON_VALUE : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isConditional(name)) {
                return Collections.emptyEnumeration();
            }
            return isAccept(name)
                    ? Collections.enumeration(Set.of(APPLICATION_JSON_VALUE))
                    : super.getHeaders(name);
        }

        @Override
        public long getDateHeader(String name) {
            return isConditional(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            for (String name : Collections.list(super.getHeaderNames())) {
                if (!isConditional(name)) {
                    names.add(name);
                }
            }
            if (names.stream().noneMatch(JsonAccept::isAccept)) {
                names.add(ACCEPT);
            }
            return Collections.enumeration(names);
        }

        private static boolean isAccept(String name) {
            return ACCEPT.equalsIgnoreCase(name);
        }

        /** The validators Spring's ResponseEntity handling would turn into a 304 (§8). */
        private static boolean isConditional(String name) {
            return IF_NONE_MATCH.equalsIgnoreCase(name) || IF_MODIFIED_SINCE.equalsIgnoreCase(name);
        }
    }
}
