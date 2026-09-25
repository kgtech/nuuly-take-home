package com.kgtech.inventoryapi.inventory;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The four spec operations (hand-written, D7). */
@RestController
@RequestMapping("/inventory")
class InventoryController {

    private final InventoryService service;

    InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/{skuId}")
    @ApiResponse(responseCode = "200", description = "Current inventory state for the sku",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class)))
    ResponseEntity<?> get(@PathVariable String skuId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping(path = "/{skuId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Current state of the item after update",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class)))
    ResponseEntity<?> create(@PathVariable String skuId, @Valid @RequestBody InventoryQuantity body) {
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping(path = "/{skuId}/purchase", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Purchase successful; remaining inventory for the item",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Insufficient inventory or invalid request",
            content = @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class)))
    ResponseEntity<?> purchase(@PathVariable String skuId, @Valid @RequestBody InventoryQuantity body) {
        throw new UnsupportedOperationException("not implemented");
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "List of all inventory items",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = InventoryItem.class))))
    List<InventoryItem> list() {
        throw new UnsupportedOperationException("not implemented");
    }

    private static ResponseEntity<?> toResponse(String skuId, StockOutcome outcome) {
        throw new UnsupportedOperationException("not implemented");
    }
}
