package com.kgtech.inventoryapi.inventory.web;

import java.io.IOException;

/** A v2 write body grew past the cap while being read (chunked, no Content-Length): 400 "Invalid request". */
final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    BodyTooLargeException(long cap) {
        super("request body exceeds " + cap + " bytes");
    }
}
