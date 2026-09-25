package com.kgtech.inventoryapi.inventory;

import org.springframework.jdbc.core.simple.JdbcClient;

/** JdbcClient implementation of the ledger writes (V1). */
class InventoryWritesImpl implements InventoryWrites {

    private final JdbcClient jdbc;

    InventoryWritesImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StockOutcome.Add add(String skuId, int quantity) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public StockOutcome.Purchase purchase(String skuId, int quantity) {
        throw new UnsupportedOperationException("not implemented");
    }
}
