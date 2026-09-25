package com.kgtech.inventoryapi.inventory;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** GET /inventory/** ignores the Accept header: it is presented as application/json (U2). */
@Component
final class JsonAcceptForGetFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        throw new UnsupportedOperationException("not implemented");
    }

    private static final class JsonAccept extends HttpServletRequestWrapper {

        JsonAccept(HttpServletRequest request) {
            super(request);
        }
    }
}
