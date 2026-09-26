package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

/** The four spec operations (hand-written, D7). */
@RestController
@RequestMapping("/inventory")
class InventoryController {

    private static final String LIMIT = "limit";
    private static final String AFTER = "after";

    private final InventoryService service;

    InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/{skuId}")
    @ApiResponse(responseCode = "200", description = "Current inventory state for the sku",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> get(@PathVariable String skuId) {
        return service.find(skuId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(TextErrors::skuNotFound);
    }

    @PostMapping(path = "/{skuId}", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Current state of the item after update",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> create(@PathVariable String skuId, @Valid @RequestBody InventoryQuantity body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = false,
                    description = "Optional UUID. The same key with the same request replays the first response. A different request, or a key older than 24h, returns 400.",
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.add(skuId, body.quantity(), idempotencyKey));
    }

    @PostMapping(path = "/{skuId}/purchase", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Purchase successful; remaining inventory for the item",
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = InventoryItem.class)))
    @ApiResponse(responseCode = "400", description = "Insufficient inventory or invalid request",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> purchase(@PathVariable String skuId, @Valid @RequestBody InventoryQuantity body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = false,
                    description = "Optional UUID. The same key with the same request replays the first response. A different request, or a key older than 24h, returns 400.",
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.purchase(skuId, body.quantity(), idempotencyKey));
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "List of all inventory items",
            headers = @Header(name = LINK, description = "Next page, when there is one: <URL>; rel=\"next\"",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = InventoryItem.class))))
    @ApiResponse(responseCode = "400", description = "Invalid request: the query string can't be decoded or repeats after",
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> list(
            @Parameter(description = "Optional page size, 1 to 250. Larger values mean 250; other values are ignored.",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "250"))
            @RequestParam(name = LIMIT, required = false) String limit,
            @Parameter(description = "Optional cursor: return only SKUs whose skuId sorts after this value. It must not be repeated.",
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

    /** G9: absolute next-page URL from the current request; other query params dropped, after strictly encoded. */
    private static String nextLink(Next next) {
        String url = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .replaceQuery(null)
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
