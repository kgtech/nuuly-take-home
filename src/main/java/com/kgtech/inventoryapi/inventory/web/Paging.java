package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.kgtech.inventoryapi.inventory.Page.Next;

/** The list's paging rules (A37): the repeated-after check (Z3) and the next-page Link (G9, C2). */
final class Paging {

    private Paging() {
    }

    /** Z3: the cursor is one sku_id, so a second value (even an empty one) makes the request ambiguous. */
    static boolean repeatsAfter(HttpServletRequest request) {
        String[] values = request.getParameterValues(AFTER);
        return values != null && values.length > 1;
    }

    /**
     * G9, C2: absolute next-page URL from the request's scheme, host, port and context path plus the routed path
     * /inventory, never the raw request URI; only limit and after, with after strictly encoded.
     */
    static String nextLink(Next next) {
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(BASE_PATH)
                .queryParam(LIMIT, next.limit())
                .queryParam(AFTER, "{after}")
                .encode()
                .buildAndExpand(next.after())
                .toUriString();
        return "<" + url + ">; rel=\"next\"";
    }
}
