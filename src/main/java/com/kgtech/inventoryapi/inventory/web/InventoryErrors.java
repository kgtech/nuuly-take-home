package com.kgtech.inventoryapi.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.kgtech.inventoryapi.web.TextErrors;

/**
 * The inventory feature's error answers (G6; DESIGN-V2 §8, A23, A24). Each is built through web's TextErrors.of, so
 * S5 keeps one Content-Type path; the generic answers (400 "Invalid request", 500) stay in TextErrors (A37, R1-05).
 */
final class InventoryErrors {

    static final String SKU_NOT_FOUND = "SKU not found";
    static final String INSUFFICIENT_INVENTORY = "Insufficient inventory";
    /** DESIGN-V2 §8: the v2 create on an existing SKU; the text names what to do instead (A23). */
    static final String SKU_EXISTS = "SKU already exists. Set its details with PUT /v2/inventory/{skuId}; "
            + "add stock with POST /inventory/{skuId}.";
    /** DESIGN-V2 §8: a conditional PUT whose If-Match no longer matches (A24). */
    static final String DETAILS_CHANGED = "Details changed since you read them. Reload the SKU and retry with "
            + "its new ETag.";

    private InventoryErrors() {
    }

    static ResponseEntity<String> skuNotFound() {
        return TextErrors.of(HttpStatus.NOT_FOUND, SKU_NOT_FOUND);
    }

    static ResponseEntity<String> insufficientInventory() {
        return TextErrors.of(HttpStatus.BAD_REQUEST, INSUFFICIENT_INVENTORY);
    }

    static ResponseEntity<String> skuExists() {
        return TextErrors.of(HttpStatus.CONFLICT, SKU_EXISTS);
    }

    static ResponseEntity<String> detailsChanged() {
        return TextErrors.of(HttpStatus.PRECONDITION_FAILED, DETAILS_CHANGED);
    }
}
