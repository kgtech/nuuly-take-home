package com.kgtech.inventoryapi.inventory.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Request body (spec InventoryQuantity). Integer so null/missing isn't 0 (G13, V2). */
record InventoryQuantity(@NotNull @Min(1) Integer quantity) {
}
