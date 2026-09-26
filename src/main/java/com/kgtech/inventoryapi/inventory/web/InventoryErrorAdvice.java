package com.kgtech.inventoryapi.inventory.web;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Maps every thrown error to a text/plain response (D6, S5, S6, G6, T3, U2, Z3), except on /actuator/** and the
 * springdoc paths, where it rethrows so Spring Boot's own error handling answers (S6, C1). An undecodable query string
 * is the exception: it is answered here on every path (C1, Z3).
 */
@RestControllerAdvice
class InventoryErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(InventoryErrorAdvice.class);

    /** Paths that keep library behaviour (S6, G10); /error is not one of them. */
    private static final List<PathPattern> LIBRARY_PATHS = List.of(
            "/actuator/**", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**")
            .stream()
            .map(PathPatternParser.defaultInstance::parse)
            .toList();

    /** Malformed or missing body, failed @Valid, wrong or missing Content-Type: 400, not 415 (G3, G13). */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            HttpMediaTypeNotSupportedException.class})
    ResponseEntity<String> invalidRequest(Exception ex, HttpServletRequest request) throws Exception {
        leaveLibraryPathsToSpring(ex, request);
        log.debug("Invalid request: {}", ex.getMessage());
        return TextErrors.invalidRequest();
    }

    /**
     * A query string Tomcat can't decode (malformed percent-escape or invalid UTF-8) is a client error on every path,
     * library paths included: never rethrown, so Tomcat logs no ERROR for it. One WARN line with the method and path;
     * no stack trace and no raw query (C1, Z3).
     */
    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<String> undecodableQuery(HttpServletRequest request) {
        log.warn("Undecodable query string on {} {}", request.getMethod(), request.getRequestURI());
        return TextErrors.invalidRequest();
    }

    /** A POST whose Accept excludes JSON is a client error on the spec's operations (U2, Y1). */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<String> notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request)
            throws Exception {
        leaveLibraryPathsToSpring(ex, request);
        if (HttpMethod.POST.matches(request.getMethod())) {
            return TextErrors.invalidRequest();
        }
        return TextErrors.of(HttpStatus.NOT_ACCEPTABLE, TextErrors.textFor(HttpStatus.NOT_ACCEPTABLE));
    }

    /** Framework errors keep their status (404, 405 with Allow, ...); anything else is a 500 (S6, T3). */
    @Hidden
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> anyOther(Exception ex, HttpServletRequest request) throws Exception {
        leaveLibraryPathsToSpring(ex, request);
        if (ex instanceof ErrorResponse error && !error.getStatusCode().is5xxServerError()) {
            HttpStatusCode status = error.getStatusCode();
            log.debug("{} on {} {}: {}", status, request.getMethod(), request.getRequestURI(), ex.getMessage());
            return TextErrors.of(status, error.getHeaders(), TextErrors.textFor(status));
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return TextErrors.internalServerError();
    }

    /**
     * Rethrows {@code ex} on a library path: Spring's exception resolution then continues without this advice
     * (DefaultHandlerExceptionResolver and Spring Boot's /error), so those paths keep their own responses (S6, C1).
     */
    private static void leaveLibraryPathsToSpring(Exception ex, HttpServletRequest request) throws Exception {
        PathContainer path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication();
        for (PathPattern pattern : LIBRARY_PATHS) {
            if (pattern.matches(path)) {
                throw ex;
            }
        }
    }
}
