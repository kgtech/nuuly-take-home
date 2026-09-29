package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.SkuId.MAX_LENGTH;
import static com.kgtech.inventoryapi.inventory.SkuId.PATTERN_REGEX;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.API_TITLE;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.API_VERSION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.CREATE_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.CREATE_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.CREATE_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.GET_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.GET_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LINK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PURCHASE_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PURCHASE_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PURCHASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PURCHASE_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_ID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_NOT_FOUND_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.TAG;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.InventoryPage;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.StockOutcome.Insufficient;
import com.kgtech.inventoryapi.inventory.StockOutcome.NotFound;
import com.kgtech.inventoryapi.inventory.StockOutcome.Ok;
import com.kgtech.inventoryapi.inventory.StockOutcome.Overflow;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.kgtech.inventoryapi.inventory.WriteResult.InvalidRequest;
import com.kgtech.inventoryapi.inventory.WriteResult.Stored;

/** The four spec operations (hand-written, D7), with the spec's info, operationIds and summaries. */
@OpenAPIDefinition(info = @Info(title = API_TITLE, version = API_VERSION))
@Tag(name = TAG)
@RestController
@RequestMapping(BASE_PATH)
class InventoryController {

    private final InventoryService service;
    private final OutcomeResponses outcomes;

    InventoryController(InventoryService service, OutcomeResponses outcomes) {
        this.service = service;
        this.outcomes = outcomes;
    }

    @GetMapping(SKU_PATH)
    @Operation(operationId = "getInventory", summary = GET_SUMMARY)
    @ApiResponse(responseCode = "200", description = GET_OK_DESCRIPTION,
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "404", description = SKU_NOT_FOUND_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> get(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId) {
        return service.find(skuId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(TextErrors::skuNotFound);
    }

    @PostMapping(path = SKU_PATH, consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "createInventory", summary = CREATE_SUMMARY)
    @ApiResponse(responseCode = "200", description = CREATE_OK_DESCRIPTION,
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = CREATE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> create(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @Valid @RequestBody InventoryQuantity body,
            HttpServletRequest request) {
        if (hasIdempotencyKey(request)) {
            return TextErrors.invalidRequest();
        }
        return toResponse(skuId, service.add(skuId, body.quantity()));
    }

    @PostMapping(path = PURCHASE_PATH, consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "purchaseItem", summary = PURCHASE_SUMMARY)
    @ApiResponse(responseCode = "200", description = PURCHASE_OK_DESCRIPTION,
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = PURCHASE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "404", description = SKU_NOT_FOUND_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> purchase(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @Valid @RequestBody InventoryQuantity body,
            HttpServletRequest request) {
        if (hasIdempotencyKey(request)) {
            return TextErrors.invalidRequest();
        }
        return toResponse(skuId, service.purchase(skuId, body.quantity()));
    }

    @GetMapping
    @Operation(operationId = "listInventory", summary = LIST_SUMMARY, description = LIST_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = LIST_OK_DESCRIPTION,
            headers = @Header(name = LINK, description = LINK_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = InventoryItem.class))))
    @ApiResponse(responseCode = "400", description = LIST_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> list(
            @Parameter(description = AFTER_DESCRIPTION,
                    schema = @Schema(type = "string"))
            @RequestParam(name = AFTER, required = false) String after,
            HttpServletRequest request) {
        if (Paging.afterRepeated(request)) {
            return TextErrors.invalidRequest();
        }
        InventoryPage page = service.list(after);
        return page.next()
                .<ResponseEntity<?>>map(next -> ResponseEntity.ok()
                        .header(LINK, Paging.nextLink(BASE_PATH, next.after())).body(page.items()))
                .orElseGet(() -> ResponseEntity.ok(page.items()));
    }

    /** OD-4: the spec has no Idempotency-Key, so any header line, whatever its value, refuses the request. */
    private static boolean hasIdempotencyKey(HttpServletRequest request) {
        return request.getHeaders(IDEMPOTENCY_KEY).hasMoreElements();
    }

    /**
     * One mapping for every write result (C-17, issue #28): a stock outcome is rendered through the same
     * OutcomeResponses.toStored that a keyed request stores, so unkeyed and replayed responses are byte-identical (Y4).
     */
    private ResponseEntity<?> toResponse(String skuId, WriteResult result) {
        return switch (result) {
            case Stored stored -> StoredResponses.toResponseEntity(stored.response());
            case InvalidRequest _ -> TextErrors.invalidRequest();
            case Ok _, NotFound _, Insufficient _, Overflow _ ->
                    StoredResponses.toResponseEntity(outcomes.toStored(skuId, result));
        };
    }
}
