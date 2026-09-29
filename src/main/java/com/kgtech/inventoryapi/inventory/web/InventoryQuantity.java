package com.kgtech.inventoryapi.inventory.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Request body (spec InventoryQuantity). Integer so null/missing isn't 0 (G13, V2). */
record InventoryQuantity(@NotNull @Min(1) @Schema(example = "10") Integer quantity) {
}
