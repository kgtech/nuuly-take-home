package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** What both list operations share: the repeated-after check (Z3) and the next-page Link (C2). */
final class Paging {

    /** Z3: the cursor is one sku_id, so a second value (even an empty one) makes the request ambiguous. */
    static boolean afterRepeated(HttpServletRequest request) {
        String[] values = request.getParameterValues(AFTER);
        return values != null && values.length > 1;
    }

    /** The unversioned Link (OD-5): only after. */
    static String nextLink(String basePath, String after) {
        return link(basePath, null, after);
    }

    /** The /v2 Link: the page size used, then after. */
    static String nextLink(String basePath, int limit, String after) {
        return link(basePath, limit, after);
    }

    /**
     * C2: absolute next-page URL from the request's scheme, host, port and context path plus the routed path, never the
     * raw request URI; after strictly encoded.
     */
    private static String link(String basePath, Integer limit, String after) {
        var builder = ServletUriComponentsBuilder.fromCurrentContextPath().path(basePath);
        if (limit != null) {
            builder.queryParam(LIMIT, limit);
        }
        String url = builder.queryParam(AFTER, "{after}").encode().buildAndExpand(after).toUriString();
        return "<" + url + ">; rel=\"next\"";
    }

    private Paging() {
    }
}
