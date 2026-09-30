package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

/**
 * One page of a list (G9, C2), GET /inventory's items or GET /v2/inventory's (A38): the items in skuId order, and the
 * cursor for the next page when there is one.
 */
public record Page<T>(List<T> items, Optional<Next> next) {

    /** The normalized limit and the last skuId of this page. */
    public record Next(int limit, String after) {
    }
}
