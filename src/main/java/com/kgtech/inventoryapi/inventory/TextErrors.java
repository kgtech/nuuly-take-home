package com.kgtech.inventoryapi.inventory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/** The single helper for text/plain error responses (S5, D6, G6, T3). */
final class TextErrors {

    static final String SKU_NOT_FOUND = "SKU not found";
    static final String INSUFFICIENT_INVENTORY = "Insufficient inventory";
    static final String INVALID_REQUEST = "Invalid request";
    static final String INTERNAL_SERVER_ERROR = "Internal server error";

    private TextErrors() {
    }

    static ResponseEntity<String> of(HttpStatusCode status, String body) {
        throw new UnsupportedOperationException("not implemented");
    }

    static ResponseEntity<String> of(HttpStatusCode status, HttpHeaders headers, String body) {
        throw new UnsupportedOperationException("not implemented");
    }

    static ResponseEntity<String> skuNotFound() {
        throw new UnsupportedOperationException("not implemented");
    }

    static ResponseEntity<String> insufficientInventory() {
        throw new UnsupportedOperationException("not implemented");
    }

    static ResponseEntity<String> invalidRequest() {
        throw new UnsupportedOperationException("not implemented");
    }

    static ResponseEntity<String> internalServerError() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 400 → INVALID_REQUEST, 500 → INTERNAL_SERVER_ERROR, else the standard reason phrase (T3). */
    static String textFor(HttpStatusCode status) {
        throw new UnsupportedOperationException("not implemented");
    }
}
