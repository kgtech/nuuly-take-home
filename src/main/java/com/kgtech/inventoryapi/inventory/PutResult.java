package com.kgtech.inventoryapi.inventory;

/** Results of PUT /v2/inventory/{skuId}/details (OD-6, OD-11); never thrown. */
public sealed interface PutResult {

    /** 201: the SKU did not exist; it now has quantity 0 and these details at version 1. */
    record Created(SkuItem item) implements PutResult {
    }

    /** 200: the SKU's details were replaced. */
    record Replaced(SkuItem item) implements PutResult {
    }

    /** 412: If-Match matches no current version, or names an absent SKU, or If-None-Match: * met an existing SKU. */
    record PreconditionFailed() implements PutResult {
    }

    /** A malformed skuId (G11): 400. */
    record InvalidRequest() implements PutResult {
    }
}
