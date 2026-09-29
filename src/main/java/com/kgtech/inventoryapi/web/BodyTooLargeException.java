package com.kgtech.inventoryapi.web;

import java.io.IOException;

/**
 * A v2 write body grew past the cap while being read (chunked, no Content-Length): 400 "Invalid request". Public
 * because inventory.web's CappedBodyRequest throws it and the advice here maps it (A37).
 */
public final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public BodyTooLargeException(long cap) {
        super("request body exceeds " + cap + " bytes");
    }
}
