package com.kgtech.inventoryapi.idempotency;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** The Idempotency-Key header format check (S3). */
public final class IdempotencyKey {

    private static final Pattern FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");

    private IdempotencyKey() {
    }

    /** The key, or empty unless the header is exactly the hyphenated 8-4-4-4-12 hex form ("" and null included). */
    public static Optional<UUID> parse(String header) {
        if (header == null || !FORMAT.matcher(header).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(header));
    }
}
