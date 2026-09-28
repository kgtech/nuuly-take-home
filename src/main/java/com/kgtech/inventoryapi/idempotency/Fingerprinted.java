package com.kgtech.inventoryapi.idempotency;

/**
 * A keyed request that is more than a quantity (A29): it renders its own canonical form, built field by field from
 * the parsed, validated request and never from the raw body (Y3). Two requests with the same fingerprint replay.
 */
public interface Fingerprinted {

    String fingerprint();
}
