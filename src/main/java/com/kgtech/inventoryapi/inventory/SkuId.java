package com.kgtech.inventoryapi.inventory;

import java.util.Optional;
import java.util.regex.Pattern;

import com.kgtech.inventoryapi.idempotency.Operation;

/** The skuId pattern (G11, R7, S2). */
public final class SkuId {

    /** G11: the one source of the pattern, also documented on the skuId path parameters (C2). */
    public static final String PATTERN_REGEX = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    /** G11: the longest skuId (sku.sku_id varchar(64)). */
    public static final int MAX_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile(PATTERN_REGEX);

    private SkuId() {
    }

    /** null → false; uses matcher(..).matches(), never find(); never changes case (G1). */
    static boolean isValid(String skuId) {
        return skuId != null && PATTERN.matcher(skuId).matches();
    }

    /** Malformed skuId: add and create → InvalidRequest, purchase → NotFound; valid → empty. No I/O (S2). */
    public static Optional<WriteResult> rejection(Operation operation, String skuId) {
        if (isValid(skuId)) {
            return Optional.empty();
        }
        return Optional.of(switch (operation) {
            case ADD, CREATE -> new WriteResult.InvalidRequest();
            case PURCHASE -> new StockOutcome.NotFound();
        });
    }
}
