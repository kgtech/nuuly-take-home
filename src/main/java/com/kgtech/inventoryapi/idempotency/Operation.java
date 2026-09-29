package com.kgtech.inventoryapi.idempotency;

/**
 * The keyed operations; dbValue is the idempotency row's operation. For the two spec POSTs it matches the ledger
 * reason, so one vocabulary names both (S8); the v2 create is 'create' (DESIGN-V2 §8).
 */
public enum Operation {
    ADD("add"),
    PURCHASE("purchase"),
    CREATE("create");

    private final String dbValue;

    Operation(String dbValue) {
        this.dbValue = dbValue;
    }

    String dbValue() {
        return dbValue;
    }
}
