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
        if (!SkuId.isValid(skuId)) {
            return TextErrors.skuNotFound(); // S2: no database access
        }
        return service.find(skuId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(TextErrors::skuNotFound);
    }

    @PostMapping(path = "/{skuId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Current state of the item after update",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = "text/plain", schema = @Schema(implementation = String.class)))
    ResponseEntity<?> create(@PathVariable String skuId, @Valid @RequestBody InventoryQuantity body) {
        if (!SkuId.isValid(skuId)) {
            return TextErrors.invalidRequest(); // S2; @Valid has already run (G4)
        }
        return toResponse(skuId, service.add(skuId, body.quantity()));
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
        // U3: @Valid body → [Idempotency-Key format, story 6] → skuId pattern → service
        if (!SkuId.isValid(skuId)) {
            return TextErrors.skuNotFound();
        }
        return toResponse(skuId, service.purchase(skuId, body.quantity()));
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "List of all inventory items",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = InventoryItem.class))))
    List<InventoryItem> list() {
        return service.findAll();
    }

    private static ResponseEntity<?> toResponse(String skuId, StockOutcome outcome) {
        return switch (outcome) {
            case StockOutcome.Ok ok -> ResponseEntity.ok(new InventoryItem(skuId, ok.quantity()));
            case StockOutcome.NotFound _ -> TextErrors.skuNotFound();
            case StockOutcome.Insufficient _ -> TextErrors.insufficientInventory();
            case StockOutcome.Overflow _ -> TextErrors.invalidRequest();
        };
    }
}
