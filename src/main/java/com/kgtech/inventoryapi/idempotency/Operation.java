package com.kgtech.inventoryapi.idempotency;

/**
 * The two keyed POST operations; dbValue is the idempotency row's operation and matches the ledger reason, so one
 * vocabulary names both (S8). dbValue is package-private: only the store and the request hash read it (A37).
 */
public enum Operation {
    ADD("add"),
    PURCHASE("purchase");

    private final String dbValue;

    Operation(String dbValue) {
        this.dbValue = dbValue;
    }

    String dbValue() {
        return dbValue;
    }
}
