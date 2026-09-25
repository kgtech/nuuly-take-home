package com.kgtech.inventoryapi.inventory;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps every thrown error to a text/plain response (D6, S5, S6, G6, T3, U2). */
@RestControllerAdvice
class InventoryErrorAdvice {

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            HttpMediaTypeNotSupportedException.class})
    ResponseEntity<String> invalidRequest(Exception ex) {
        throw new UnsupportedOperationException("not implemented");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<String> notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Hidden
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> anyOther(Exception ex, HttpServletRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
