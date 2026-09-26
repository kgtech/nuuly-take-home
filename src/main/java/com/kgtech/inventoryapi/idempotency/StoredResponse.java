package com.kgtech.inventoryapi.idempotency;

/** The response stored against a key and replayed unchanged (Y4). The web layer renders it. */
public record StoredResponse(int status, String contentType, String body) {
}
