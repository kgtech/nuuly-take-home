package com.kgtech.inventoryapi.inventory;

/** Response item (spec InventoryItem); quantity is the ledger SUM as long (G2). */
record InventoryItem(String skuId, long quantity) {
}
