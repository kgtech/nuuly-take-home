package com.kgtech.inventoryapi.inventory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
        return of(status, new HttpHeaders(), body);
    }

    /** Copies the headers first (e.g. Allow on 405), then fixes the Content-Type (S5). */
    static ResponseEntity<String> of(HttpStatusCode status, HttpHeaders headers, String body) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    static ResponseEntity<String> skuNotFound() {
        return of(HttpStatus.NOT_FOUND, SKU_NOT_FOUND);
    }

    static ResponseEntity<String> insufficientInventory() {
        return of(HttpStatus.BAD_REQUEST, INSUFFICIENT_INVENTORY);
    }

    static ResponseEntity<String> invalidRequest() {
        return of(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
    }

    static ResponseEntity<String> internalServerError() {
        return of(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR);
    }

    /** 400 → INVALID_REQUEST, 500 → INTERNAL_SERVER_ERROR, else the standard reason phrase (T3). */
    static String textFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> INVALID_REQUEST;
            case 500 -> INTERNAL_SERVER_ERROR;
            default -> {
                HttpStatus known = HttpStatus.resolve(status.value());
                yield known != null ? known.getReasonPhrase() : String.valueOf(status.value());
            }
        };
    }
}
