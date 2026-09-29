package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.InventoryService.DEFAULT_LIMIT;
import static com.kgtech.inventoryapi.inventory.InventoryService.MAX_LIMIT;

/** The web layer's API strings: API paths, query parameter names and OpenAPI description texts (C2, Z2). */
final class InventoryApi {

    /** The routed path; the next-page Link is built from it, never from the raw request URI (C2). */
    static final String BASE_PATH = "/inventory";
    static final String SKU_PATH = "/{skuId}";
    static final String PURCHASE_PATH = SKU_PATH + "/purchase";
    static final String DETAILS_PATH = SKU_PATH + "/details";

    /** DESIGN-V2 §8, A21: the additive v2 paths. */
    static final String V2_BASE_PATH = "/v2/inventory";
    static final String TAG_V2 = "inventory-v2";

    static final String LIMIT = "limit";
    static final String AFTER = "after";

    static final String API_TITLE = "Inventory API";
    static final String API_VERSION = "1.0.0";
    static final String TAG = "inventory";

    static final String GET_SUMMARY = "Get inventory for a SKU";
    static final String GET_OK_DESCRIPTION = "Current inventory state for the sku";
    static final String SKU_NOT_FOUND_DESCRIPTION = "SKU not found";
    static final String CREATE_SUMMARY = "Create or update inventory for a SKU";
    static final String CREATE_OK_DESCRIPTION = "Current state of the item after update";
    static final String CREATE_INVALID_DESCRIPTION = "Invalid request";
    static final String PURCHASE_SUMMARY = "Purchase a quantity of a SKU";
    static final String PURCHASE_OK_DESCRIPTION = "Purchase successful; remaining inventory for the item";
    static final String PURCHASE_INVALID_DESCRIPTION = "Insufficient inventory or invalid request";

    static final String SKU_ID_DESCRIPTION = "SKU ID: 1 to 64 characters; letters, digits, '.', '_' or '-', "
            + "starting with a letter or digit. Case-sensitive.";
    static final String V2_IDEMPOTENCY_KEY_DESCRIPTION = "Required UUID. The same key with the same request replays "
            + "the first response. A missing or malformed key, a different request, or a key older than 24h returns 400.";

    static final String LIST_SUMMARY = "List all inventory";
    static final String LIST_DESCRIPTION = "Returns SKUs sorted by skuId, at most " + MAX_LIMIT + " per response. "
            + "When more SKUs follow, the Link header holds the next page's URL (it carries the after cursor); follow "
            + "it until a response has no Link to list all inventory. If no SKUs exist, returns an empty array.";
    static final String LIST_OK_DESCRIPTION = "One page of inventory items, at most " + MAX_LIMIT
            + ", sorted by skuId; the Link header points to the next page after the last skuId";
    static final String LIST_INVALID_DESCRIPTION = "Invalid request: the query string can't be decoded or repeats after";
    static final String LINK_DESCRIPTION = "Next page, when more SKUs follow: <URL>; rel=\"next\"";
    static final String AFTER_DESCRIPTION = "Optional exclusive cursor: return only SKUs whose skuId sorts after this "
            + "value, up to " + MAX_LIMIT + ". It must not be repeated.";

    static final String LIMIT_DESCRIPTION = "Optional page size, 1 to " + MAX_LIMIT + " (default " + DEFAULT_LIMIT
            + "). Larger values mean " + MAX_LIMIT + "; other values are ignored and the default applies.";
    static final String V2_AFTER_DESCRIPTION = "Optional exclusive cursor: return only SKUs whose skuId sorts after "
            + "this value, up to the page size. It must not be repeated.";

    static final String V2_LIST_SUMMARY = "List all SKUs with details";
    static final String V2_LIST_DESCRIPTION = "Returns SKUs sorted by skuId with their quantity and details (absent "
            + "for a SKU that has none), at most " + MAX_LIMIT + " per response. The optional limit (1 to "
            + MAX_LIMIT + ", default " + MAX_LIMIT + ") sets the page size and after is an exclusive cursor; a Link "
            + "header with rel=next carries both to the next page.";
    static final String V2_LIST_OK_DESCRIPTION = "One page of SKUs with details, at most " + MAX_LIMIT
            + ", sorted by skuId";
    static final String V2_GET_SUMMARY = "Get a SKU with its details";
    static final String V2_GET_OK_DESCRIPTION = "The SKU with its quantity and details";
    static final String V2_ADD_SUMMARY = "Add stock to a SKU";
    static final String V2_ADD_DESCRIPTION = "Adds stock, creating the SKU if needed; like POST /inventory/{skuId}, "
            + "but the Idempotency-Key is required and the response is a SkuItem. The same key with the same request "
            + "replays the first response.";
    static final String V2_ADD_OK_DESCRIPTION = "The SKU after the update";
    static final String V2_PURCHASE_SUMMARY = "Purchase a quantity of a SKU";
    static final String V2_PURCHASE_DESCRIPTION = "Like POST /inventory/{skuId}/purchase, but the Idempotency-Key is "
            + "required and the response is a SkuItem. The same key with the same request replays the first response.";
    static final String V2_PURCHASE_OK_DESCRIPTION = "Purchase successful; the SKU with its remaining quantity";
    static final String V2_PRECONDITION_DESCRIPTION = TextErrors.DETAILS_CHANGED;
    static final String ETAG_DESCRIPTION = "The details version, a strong validator for If-Match; \"0\" before any "
            + "details";
    static final String PUT_CREATED_ETAG_DESCRIPTION = "The details version of a new SKU, always \"1\"";
    static final String PUT_DETAILS_SUMMARY = "Create a SKU or replace its details";
    static final String PUT_DETAILS_DESCRIPTION = "Replaces the whole SkuDetails of the SKU. A SKU that does not exist "
            + "is created with quantity 0 (201, ETag \"1\"); an existing SKU keeps its stock (200, new ETag). "
            + "If-Match (strong ETags, or \"*\" for any existing SKU) and If-None-Match: * (create only) are optional "
            + "preconditions; without one the PUT is unconditional. Any If-Match on a SKU that does not exist is 412. "
            + "Idempotency-Key is ignored: PUT is idempotent by method.";
    static final String PUT_DETAILS_CREATED_DESCRIPTION = "The SKU was created at quantity 0 with these details";
    static final String PUT_DETAILS_REPLACED_DESCRIPTION = "The SKU with its replaced details";
    static final String PUT_DETAILS_IF_MATCH_DESCRIPTION = "Optional strong ETag(s) from a previous response, or \"*\" "
            + "for any existing SKU; 412 when none matches or the SKU does not exist";
    static final String IF_NONE_MATCH_DESCRIPTION = "Optional; only \"*\" is supported: create only, 412 when the SKU "
            + "already exists. Any other value is 400";

    private InventoryApi() {
    }
}
