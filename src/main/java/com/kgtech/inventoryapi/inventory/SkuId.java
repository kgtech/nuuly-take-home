package com.kgtech.inventoryapi.inventory;

import java.util.Optional;
import java.util.regex.Pattern;

import com.kgtech.inventoryapi.idempotency.Operation;

/** The skuId pattern (G11, R7, S2). */
final class SkuId {

    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private SkuId() {
    }

    /** null → false; uses matcher(..).matches(), never find(); never changes case (G1). */
    static boolean isValid(String skuId) {
        return skuId != null && PATTERN.matcher(skuId).matches();
    }

    /** Malformed skuId: create → InvalidRequest, purchase → NotFound; valid → empty. No I/O (S2). */
    static Optional<WriteResult> rejection(Operation operation, String skuId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
