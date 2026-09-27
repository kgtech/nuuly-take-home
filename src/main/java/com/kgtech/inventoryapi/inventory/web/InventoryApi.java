package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.InventoryService.DEFAULT_LIMIT;
import static com.kgtech.inventoryapi.inventory.InventoryService.MAX_LIMIT;

/** The web layer's API strings: API paths, query parameter names and OpenAPI description texts (C2, Z2). */
final class InventoryApi {

    /** The routed path; the next-page Link is built from it, never from the raw request URI (C2). */
    static final String BASE_PATH = "/inventory";
    static final String SKU_PATH = "/{skuId}";
    static final String PURCHASE_PATH = SKU_PATH + "/purchase";

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

    private InventoryApi() {
    }
}
