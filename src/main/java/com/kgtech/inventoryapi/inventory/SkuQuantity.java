package com.kgtech.inventoryapi.inventory;

/** A SKU and its balance, the SUM of its ledger deltas. */
interface SkuQuantity {

    String getSkuId();

    long getQuantity();
}
