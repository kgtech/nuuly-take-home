package com.kgtech.inventoryapi.inventory;

/**
 * Business results of the v2 create; never thrown, stored against an Idempotency-Key like stock outcomes (R1, A28).
 * Not a WriteResult: the create returns WriteResult<DetailsOutcome>, which wraps one (A38).
 */
public sealed interface DetailsOutcome {

    /** 201: the created SKU with its details and initial stock. */
    record Created(SkuItem item) implements DetailsOutcome {
    }

    /** 409: the SKU exists (created by v2 or by the spec's add); nothing was written. */
    record AlreadyExists() implements DetailsOutcome {
    }
}
