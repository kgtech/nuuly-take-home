package com.kgtech.inventoryapi.inventory;

/** Results of a PUT of details (DESIGN-V2 §8 "Edit"); never thrown. */
public sealed interface ReplaceResult {

    record Replaced(SkuItem item) implements ReplaceResult {
    }

    record NotFound() implements ReplaceResult {
    }

    /** If-Match named versions and the current one is not among them: 412. */
    record VersionMismatch() implements ReplaceResult {
    }

    /** A malformed skuId (G11): 400, like create. */
    record InvalidRequest() implements ReplaceResult {
    }
}
