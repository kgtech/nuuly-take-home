package com.kgtech.inventoryapi.inventory.web;

import org.springframework.http.ResponseEntity;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/** Renders a stored keyed response unchanged: status, Content-Type and body (Y4). */
final class StoredResponses {

    private StoredResponses() {
    }

    static ResponseEntity<String> toResponseEntity(StoredResponse response) {
        throw new UnsupportedOperationException("not implemented");
    }
}
