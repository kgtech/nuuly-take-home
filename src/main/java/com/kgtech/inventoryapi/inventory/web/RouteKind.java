package com.kgtech.inventoryapi.inventory.web;

/**
 * The shape of a path under /inventory or /v2/inventory (F-04, L21). The request guard decides by kind and method, so a
 * route with a new shape must get a kind here before the guard can answer for it.
 */
enum RouteKind {
    /** The base path itself. */
    LIST,
    /** The base path plus a SKU segment. */
    ITEM,
    /** The SKU segment plus "purchase". */
    PURCHASE,
    /** The SKU segment plus "details"; exists only under /v2. */
    DETAILS,
    /** Any other shape under a base: no guard answer, Spring routes or rejects it. */
    OTHER
}
