package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.SkuId.MAX_LENGTH;
import static com.kgtech.inventoryapi.inventory.SkuId.PATTERN_REGEX;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.IDEMPOTENCY_KEY_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LINK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_ID_DESCRIPTION;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import java.util.List;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.InventoryPage;
import com.kgtech.inventoryapi.inventory.InventoryPage.Next;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.StockOutcome.Insufficient;
import com.kgtech.inventoryapi.inventory.StockOutcome.NotFound;
import com.kgtech.inventoryapi.inventory.StockOutcome.Ok;
import com.kgtech.inventoryapi.inventory.StockOutcome.Overflow;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.kgtech.inventoryapi.inventory.WriteResult.InvalidRequest;
import com.kgtech.inventoryapi.inventory.WriteResult.Stored;

/** The four spec operations (hand-written, D7), with the spec's info, operationIds and summaries. */
@OpenAPIDefinition(info = @Info(title = "Inventory API", version = "1.0.0"))
@Tag(name = "inventory")
@RestController
@RequestMapping(BASE_PATH)
class InventoryController {

    private final InventoryService service;

    InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/{skuId}")
    @Operation(operationId = "getInventory", summary = "Get inventory for a SKU")
    @ApiResponse(responseCode = "200", description = "Current inventory state for the sku",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> get(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId) {
        return service.find(skuId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(TextErrors::skuNotFound);
    }

    @PostMapping(path = "/{skuId}", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "createInventory", summary = "Create or update inventory for a SKU")
    @ApiResponse(responseCode = "200", description = "Current state of the item after update",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> create(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @Valid @RequestBody InventoryQuantity body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = false,
                    description = IDEMPOTENCY_KEY_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.add(skuId, body.quantity(), idempotencyKey));
    }

    @PostMapping(path = "/{skuId}/purchase", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "purchaseItem", summary = "Purchase a quantity of a SKU")
    @ApiResponse(responseCode = "200", description = "Purchase successful; remaining inventory for the item",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Insufficient inventory or invalid request",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> purchase(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @Valid @RequestBody InventoryQuantity body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = false,
                    description = IDEMPOTENCY_KEY_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.purchase(skuId, body.quantity(), idempotencyKey));
    }

    @GetMapping
    @Operation(operationId = "listInventory", summary = "List all inventory", description = LIST_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = LIST_OK_DESCRIPTION,
            headers = @Header(name = LINK, description = LINK_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = InventoryItem.class))))
    @ApiResponse(responseCode = "400", description = LIST_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> list(
            @Parameter(description = LIMIT_DESCRIPTION,
                    schema = @Schema(type = "integer", minimum = "1", maximum = "250", defaultValue = "250"))
            @RequestParam(name = LIMIT, required = false) String limit,
            @Parameter(description = AFTER_DESCRIPTION,
                    schema = @Schema(type = "string"))
            @RequestParam(name = AFTER, required = false) String after,
            HttpServletRequest request) {
        if (isRepeated(request, AFTER)) {
            return TextErrors.invalidRequest();
        }
        InventoryPage page = service.list(limit, after);
        return page.next()
                .<ResponseEntity<?>>map(next -> ResponseEntity.ok().header(LINK, nextLink(next)).body(page.items()))
                .orElseGet(() -> ResponseEntity.ok(page.items()));
    }

    /** Z3: the cursor is one sku_id, so a second value (even an empty one) makes the request ambiguous. */
    private static boolean isRepeated(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length > 1;
    }

    /**
     * G9, C2: absolute next-page URL from the request's scheme, host, port and context path plus the routed path, never
     * the raw request URI; only limit and after, with after strictly encoded.
     */
    private static String nextLink(Next next) {
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(BASE_PATH)
                .queryParam(LIMIT, next.limit())
                .queryParam(AFTER, "{after}")
                .encode()
                .buildAndExpand(next.after())
                .toUriString();
        return "<" + url + ">; rel=\"next\"";
    }

    /** Maps every write result; keyed responses (first and replayed) are sent as stored (Y4). */
    private static ResponseEntity<?> toResponse(String skuId, WriteResult result) {
        return switch (result) {
            case Ok ok -> ResponseEntity.ok(new InventoryItem(skuId, ok.quantity()));
            case NotFound _ -> TextErrors.skuNotFound();
            case Insufficient _ -> TextErrors.insufficientInventory();
            case Overflow _, InvalidRequest _ -> TextErrors.invalidRequest();
            case Stored stored -> StoredResponses.toResponseEntity(stored.response());
        };
    }
}
