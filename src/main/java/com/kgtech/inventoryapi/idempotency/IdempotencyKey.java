package com.kgtech.inventoryapi.idempotency;

import java.util.UUID;
import java.util.regex.Pattern;

/** The Idempotency-Key header format check (S3). */
final class IdempotencyKey {

    private static final Pattern FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");

    private IdempotencyKey() {
    }

    /** null → false; the header must match the hyphenated 8-4-4-4-12 hex form exactly. */
    static boolean isValid(String header) {
        return header != null && FORMAT.matcher(header).matches();
    }

    /** The key as a UUID; IllegalArgumentException unless {@link #isValid(String)}. */
    static UUID parse(String header) {
        if (!isValid(header)) {
            throw new IllegalArgumentException("Idempotency-Key must be a hyphenated UUID");
        }
        return UUID.fromString(header);
    }
}
