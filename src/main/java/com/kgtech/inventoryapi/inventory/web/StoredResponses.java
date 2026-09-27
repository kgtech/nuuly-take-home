package com.kgtech.inventoryapi.inventory.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/** Renders a stored keyed response unchanged: status, Content-Type and body (Y4). */
final class StoredResponses {

    private StoredResponses() {
    }

    static ResponseEntity<String> toResponseEntity(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, response.contentType())
                .body(response.body());
    }
}
