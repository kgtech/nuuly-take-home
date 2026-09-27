package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static org.springframework.http.HttpHeaders.ACCEPT;

import java.io.IOException;
import java.util.List;

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
 * Two request checks Spring MVC would get wrong on /inventory/** (issue #23, C-04, C-34):
 * a raw ';' in the SKU segment (Spring strips ";matrix" content before binding, so /inventory/ABC-1;lot=7 would reach
 * SKU ABC-1) is answered as the malformed SKU ID it is (G11: create 400, GET and purchase 404); and a POST whose
 * Accept gives every JSON type q=0 is 400 (U2, Y1), which the produces condition alone accepts. Nothing is written.
 */
@Component
final class InventoryRequestGuardFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = uri.startsWith(context) ? uri.substring(context.length()) : uri;
        return !path.startsWith(BASE_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String path = uri.substring(request.getContextPath().length());
        String[] segments = path.substring((BASE_PATH + "/").length()).split("/", -1);
        boolean get = HttpMethod.GET.matches(request.getMethod());
        boolean post = HttpMethod.POST.matches(request.getMethod());
        boolean purchase = segments.length == 2 && segments[1].equals("purchase");
        boolean single = segments.length == 1;
        if ((get && single || post && (single || purchase)) && segments[0].indexOf(';') >= 0) {
            write(response, post && single ? TextErrors.invalidRequest() : TextErrors.skuNotFound());
            return;
        }
        if (post && (single || purchase) && !acceptsJson(request)) {
            write(response, TextErrors.invalidRequest());
            return;
        }
        chain.doFilter(request, response);
    }

    /** No Accept, or any JSON-compatible type with q > 0, accepts JSON. Unparseable Accept is left to Spring. */
    private static boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader(ACCEPT);
        if (accept == null || accept.isBlank()) {
            return true;
        }
        List<MediaType> types;
        try {
            types = MediaType.parseMediaTypes(accept);
        } catch (InvalidMediaTypeException e) {
            return true;
        }
        return types.stream().anyMatch(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON) && t.getQualityValue() > 0);
    }

    private static void write(HttpServletResponse response, ResponseEntity<String> error) throws IOException {
        response.setStatus(error.getStatusCode().value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.valueOf(error.getBody()));
        response.flushBuffer();
    }
}
