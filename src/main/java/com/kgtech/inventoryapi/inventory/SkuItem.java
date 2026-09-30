package com.kgtech.inventoryapi.inventory;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The v2 representation of a SKU (DESIGN-V2 §8): the quantity from the same balance row as the spec's item, and the
 * details when the SKU has any. {@code detailsVersion} is the ETag's value ("0" before any details), not a body field.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record SkuItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String skuId,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED) long quantity,
        @Schema(implementation = SkuDetails.class) Optional<SkuDetails> details,
        @JsonIgnore @Schema(hidden = true) long detailsVersion) {
}
