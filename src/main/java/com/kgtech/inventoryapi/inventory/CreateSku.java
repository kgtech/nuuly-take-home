package com.kgtech.inventoryapi.inventory;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.kgtech.inventoryapi.idempotency.Fingerprinted;

/**
 * The v2 create request (DESIGN-V2 §8 "Create"): the details plus optional initial stock. Its fingerprint is the
 * canonical, length-prefixed rendering of every field (Y3, A29), so two requests replay only when they are the same
 * request, and a newline inside a value cannot collide with a field boundary.
 */
@Schema(name = "CreateSkuRequest")
public record CreateSku(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SkuDetails details,
        @Schema(description = "Stock to record at creation (default 0), through the same ledger as an add",
                minimum = "0", defaultValue = "0") Integer initialQuantity) implements Fingerprinted {

    public CreateSku {
        if (details == null) {
            throw new IllegalArgumentException("details is required");
        }
        initialQuantity = initialQuantity == null ? 0 : initialQuantity;
        if (initialQuantity < 0) {
            throw new IllegalArgumentException("initialQuantity must be >= 0");
        }
    }

    @Override
    public String fingerprint() {
        StringBuilder out = new StringBuilder();
        field(out, details.name());
        field(out, details.description());
        field(out, details.cost().map(c -> c.amount() + " " + c.currency()).orElse("-"));
        List<String> images = details.images();
        field(out, Integer.toString(images.size()));
        for (String image : images) {
            field(out, image);
        }
        field(out, Integer.toString(initialQuantity));
        return out.toString();
    }

    private static void field(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value).append('\n');
    }
}
