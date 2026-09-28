package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

/** One page of the v2 list (G9 rules): the items, and the cursor for the next page when there is one. */
public record SkuPage(List<SkuItem> items, Optional<InventoryPage.Next> next) {
}
