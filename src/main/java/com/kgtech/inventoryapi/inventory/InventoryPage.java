package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

/** One page of the list (G9): the items, and the cursor for the next page when there is one. */
public record InventoryPage(List<InventoryItem> items, Optional<Next> next) {

    /** The normalized limit and the last skuId of this page. */
    public record Next(int limit, String after) {
    }
}
