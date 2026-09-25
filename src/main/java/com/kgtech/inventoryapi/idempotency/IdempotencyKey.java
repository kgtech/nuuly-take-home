package com.kgtech.inventoryapi.idempotency;

import java.util.UUID;

/** The Idempotency-Key header format check (S3). */
public final class IdempotencyKey {

    private IdempotencyKey() {
    }

    /** null → false; the header must match the hyphenated 8-4-4-4-12 hex form exactly. */
    public static boolean isValid(String header) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** The key as a UUID; IllegalArgumentException unless {@link #isValid(String)}. */
    public static UUID parse(String header) {
        throw new UnsupportedOperationException("not implemented");
    }
}
