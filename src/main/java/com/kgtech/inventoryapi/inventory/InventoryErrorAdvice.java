package com.kgtech.inventoryapi.inventory;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps every thrown error to a text/plain response (D6, S5, S6, G6, T3, U2). */
@RestControllerAdvice
class InventoryErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(InventoryErrorAdvice.class);

    /** Malformed or missing body, failed @Valid, wrong or missing Content-Type: 400, not 415 (G3, G13). */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            HttpMediaTypeNotSupportedException.class})
    ResponseEntity<String> invalidRequest(Exception ex) {
        log.debug("Invalid request: {}", ex.getMessage());
        return TextErrors.invalidRequest();
    }

    /** A POST whose Accept excludes JSON is a client error on the spec's operations (U2, Y1). */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<String> notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        if (HttpMethod.POST.matches(request.getMethod())) {
            return TextErrors.invalidRequest();
        }
        return TextErrors.of(HttpStatus.NOT_ACCEPTABLE, TextErrors.textFor(HttpStatus.NOT_ACCEPTABLE));
    }

    /** Framework errors keep their status (404, 405 with Allow, ...); anything else is a 500 (S6, T3). */
    @Hidden
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> anyOther(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse error && !error.getStatusCode().is5xxServerError()) {
            HttpStatusCode status = error.getStatusCode();
            log.debug("{} on {} {}: {}", status, request.getMethod(), request.getRequestURI(), ex.getMessage());
            return TextErrors.of(status, error.getHeaders(), TextErrors.textFor(status));
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return TextErrors.internalServerError();
    }
}
