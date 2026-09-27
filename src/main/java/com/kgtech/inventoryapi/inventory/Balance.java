package com.kgtech.inventoryapi.inventory;

/** A SKU's current quantity and the version that changes with it (DESIGN-V2 §1). */
record Balance(long quantity, long version) {
}
