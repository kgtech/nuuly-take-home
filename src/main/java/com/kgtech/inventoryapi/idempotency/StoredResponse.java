package com.kgtech.inventoryapi.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The response stored against a key and replayed unchanged (Y4). */
public record StoredResponse(int status, String contentType, String body) {

    public ResponseEntity<String> toResponseEntity() {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(contentType))
                .body(body);
    }
}
