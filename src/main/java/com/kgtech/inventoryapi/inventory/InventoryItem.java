package com.kgtech.inventoryapi.inventory;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response item (spec InventoryItem); quantity is the balance row (E1), a long (G2), never negative (minimum 0). */
public record InventoryItem(String skuId, @Schema(minimum = "0") long quantity) {
}
