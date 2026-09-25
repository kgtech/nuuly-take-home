package com.kgtech.inventoryapi.inventory;

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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** GET /inventory/** ignores the Accept header: it is presented as application/json (U2). */
@Component
final class JsonAcceptForGetFilter extends OncePerRequestFilter {

    private static final String BASE = "/inventory";

    /** Only GET /inventory and GET /inventory/**; springdoc, actuator and every POST are untouched (S6). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String path = routedPath(request);
        return !(path.equals(BASE) || path.startsWith(BASE + "/"));
    }

    /** requestURI minus contextPath, decoded and without ";" parameters: the path Spring matches handlers on. */
    private static String routedPath(HttpServletRequest request) {
        StringBuilder path = new StringBuilder();
        for (PathContainer.Element element
                : RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication().elements()) {
            path.append(element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
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
            return isAccept(name) ? MediaType.APPLICATION_JSON_VALUE : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isAccept(name)
                    ? Collections.enumeration(Set.of(MediaType.APPLICATION_JSON_VALUE))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            if (names.stream().noneMatch(JsonAccept::isAccept)) {
                names.add(HttpHeaders.ACCEPT);
            }
            return Collections.enumeration(names);
        }

        private static boolean isAccept(String name) {
            return HttpHeaders.ACCEPT.equalsIgnoreCase(name);
        }
    }
}
