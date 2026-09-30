package com.kgtech.inventoryapi.inventory.web;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Boot's error controller is not an API (M-02). Two cases answer text/plain here, never Boot's JSON or Whitelabel page
 * (G10, S5, T3):
 * <ul>
 * <li>a client request straight to /error, on any method and with any Accept, is 404 "Not Found";
 * <li>the container's ERROR dispatch to /error, for an error Tomcat reports outside Spring MVC (a bad chunk-size line
 * fails while the body is read), gets the status Tomcat set and {@link TextErrors#textFor} for it.
 * </ul>
 * An error on a library path (actuator, springdoc) is passed on to Boot's controller, so those paths keep their body
 * (S6, C1).
 */
@Component
final class ErrorPathFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            if (isLibraryPath(request)) {
                chain.doFilter(request, response);
            } else {
                TextErrors.write(response, TextErrors.of(errorStatus(request), TextErrors.textFor(errorStatus(request))));
            }
        } else if (RoutedPath.isErrorPath(request)) {
            TextErrors.write(response, TextErrors.of(HttpStatus.NOT_FOUND, TextErrors.textFor(HttpStatus.NOT_FOUND)));
        } else {
            chain.doFilter(request, response);
        }
    }

    /** The failed request's own path, which the container keeps as an attribute of the ERROR dispatch. */
    private static boolean isLibraryPath(HttpServletRequest request) {
        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri != null
                && LibraryPaths.matches(RequestPath.parse(uri, request.getContextPath()).pathWithinApplication());
    }

    private static HttpStatusCode errorStatus(HttpServletRequest request) {
        return request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                ? HttpStatusCode.valueOf(code) : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
