package com.kgtech.inventoryapi.idempotency;

/** The two keyed POST operations; dbValue matches the ledger reason (plan OQ3). */
public enum Operation {
    ADD("add"),
    PURCHASE("purchase");

    private final String dbValue;

    Operation(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
