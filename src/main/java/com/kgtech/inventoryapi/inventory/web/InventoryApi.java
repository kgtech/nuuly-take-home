package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.InventoryService.DEFAULT_LIMIT;
import static com.kgtech.inventoryapi.inventory.InventoryService.MAX_LIMIT;

/** The web layer's API strings: API paths, query parameter names and OpenAPI description texts (C2, Z2). */
final class InventoryApi {

    /** The routed path; the next-page Link is built from it, never from the raw request URI (C2). */
    static final String BASE_PATH = "/inventory";
    static final String SKU_PATH = "/{skuId}";
    static final String PURCHASE_PATH = SKU_PATH + "/purchase";

    /** DESIGN-V2 §8, A21: the additive v2 paths. */
    static final String V2_BASE_PATH = "/v2/inventory";
    static final String TAG_V2 = "inventory-v2";

    static final String LIMIT = "limit";
    static final String AFTER = "after";

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
    static final String IDEMPOTENCY_KEY_DESCRIPTION = "Optional UUID. The same key with the same request replays "
            + "the first response. A different request, or a key older than 24h, returns 400.";

    static final String LIST_SUMMARY = "List all inventory";
    static final String LIST_DESCRIPTION = "Returns SKUs sorted by skuId, at most " + MAX_LIMIT + " per response. "
            + "When more SKUs follow, the Link header holds the next page's URL; follow it until a response has no "
            + "Link to list all inventory. If no SKUs exist, returns an empty array.";
    static final String LIST_OK_DESCRIPTION = "One page of inventory items, at most " + MAX_LIMIT + ", sorted by skuId";
    static final String LIST_INVALID_DESCRIPTION = "Invalid request: the query string can't be decoded or repeats after";
    static final String LINK_DESCRIPTION = "Next page, when more SKUs follow: <URL>; rel=\"next\"";
    static final String LIMIT_DESCRIPTION = "Optional page size, 1 to " + MAX_LIMIT + " (default " + DEFAULT_LIMIT
            + "). Larger values mean " + MAX_LIMIT + "; other values are ignored and the default applies.";
    static final String AFTER_DESCRIPTION = "Optional cursor: return only SKUs whose skuId sorts after this value, "
            + "up to the page size. It must not be repeated.";

    static final String V2_LIST_SUMMARY = "List all SKUs with details";
    static final String V2_LIST_DESCRIPTION = "Returns SKUs sorted by skuId with their quantity and details (absent "
            + "for a SKU that has none), at most " + MAX_LIMIT + " per response; the same limit, after and Link rules "
            + "as GET /inventory.";
    static final String V2_LIST_OK_DESCRIPTION = "One page of SKUs with details, at most " + MAX_LIMIT
            + ", sorted by skuId";
    static final String V2_GET_SUMMARY = "Get a SKU with its details";
    static final String V2_GET_OK_DESCRIPTION = "The SKU with its quantity and details";
    static final String V2_CREATE_SUMMARY = "Create a SKU with details";
    static final String V2_CREATE_DESCRIPTION = "Creates the SKU with its details and optional initial stock in one "
            + "transaction; initial stock is recorded in the ledger like an add. A SKU that already exists (including "
            + "one created by POST /inventory/{skuId}) is 409; set its details with PUT and add stock with "
            + "POST /inventory/{skuId}.";
    static final String V2_CREATED_DESCRIPTION = "The created SKU";
    static final String V2_CONFLICT_DESCRIPTION = InventoryErrors.SKU_EXISTS;
    static final String V2_REPLACE_SUMMARY = "Replace a SKU's details";
    static final String V2_REPLACE_DESCRIPTION = "Replaces the SKU's details (creates them for a SKU that has none). "
            + "Stock is not changed. With If-Match, the details are replaced only when their current ETag is one of "
            + "the listed values, else 412.";
    static final String V2_REPLACED_DESCRIPTION = "The SKU after the update";
    static final String V2_PRECONDITION_DESCRIPTION = InventoryErrors.DETAILS_CHANGED;
    static final String ETAG_DESCRIPTION = "The details version, a strong validator for If-Match; \"0\" before any "
            + "details";
    static final String CREATED_ETAG_DESCRIPTION = "The details version of a new SKU, always \"1\" (also on a replayed "
            + "201, even after a later PUT)";
    static final String IF_MATCH_DESCRIPTION = "Optional strong ETag(s) from a previous response; \"*\" or absent "
            + "means unconditional";

    private InventoryApi() {
    }
}
