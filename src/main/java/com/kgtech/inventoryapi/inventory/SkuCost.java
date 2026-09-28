package com.kgtech.inventoryapi.inventory;

import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

/** A cost in minor units of a currency (DESIGN-V2 §8 "Representation", A26); validates itself. */
@Schema(description = "A cost in minor units of a currency")
public record SkuCost(
        @Schema(description = "Minor units (e.g. cents), never negative", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long amount,
        @Schema(description = "Three-letter uppercase currency code", pattern = SkuCost.CURRENCY_REGEX,
                requiredMode = Schema.RequiredMode.REQUIRED) String currency) {

    public static final String CURRENCY_REGEX = "^[A-Z]{3}$";
    private static final Pattern CURRENCY = Pattern.compile(CURRENCY_REGEX);

    public SkuCost {
        if (amount < 0) {
            throw new IllegalArgumentException("cost.amount must be >= 0");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("cost.currency must match " + CURRENCY_REGEX);
        }
    }
}
