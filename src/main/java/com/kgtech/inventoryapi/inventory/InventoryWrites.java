package com.kgtech.inventoryapi.inventory;

/** Atomic ledger writes; run only inside the attempt's SERIALIZABLE transaction (S1, X1). */
interface InventoryWrites {

    StockOutcome.Add add(String skuId, int quantity);

    StockOutcome.Purchase purchase(String skuId, int quantity);
}
