package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.InventoryService.DEFAULT_LIMIT;
import static com.kgtech.inventoryapi.inventory.InventoryService.MAX_LIMIT;
import static com.kgtech.inventoryapi.inventory.SkuId.MAX_LENGTH;
import static com.kgtech.inventoryapi.inventory.SkuId.PATTERN_REGEX;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.CREATE_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PUT_CREATED_ETAG_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.DETAILS_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.ETAG_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.IF_NONE_MATCH_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LINK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PURCHASE_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PURCHASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PUT_DETAILS_CREATED_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PUT_DETAILS_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PUT_DETAILS_IF_MATCH_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PUT_DETAILS_REPLACED_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.PUT_DETAILS_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_ID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_NOT_FOUND_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.TAG_V2;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_ADD_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_ADD_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_ADD_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_AFTER_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_GET_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_GET_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_LIST_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_LIST_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_LIST_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_IDEMPOTENCY_KEY_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_PRECONDITION_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_PURCHASE_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_PURCHASE_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_PURCHASE_SUMMARY;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kgtech.inventoryapi.inventory.DetailsPrecondition;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.PutResult;
import com.kgtech.inventoryapi.inventory.SkuDetails;
import com.kgtech.inventoryapi.inventory.SkuItem;
import com.kgtech.inventoryapi.inventory.SkuPage;
import com.kgtech.inventoryapi.inventory.StockOutcome.Insufficient;
import com.kgtech.inventoryapi.inventory.StockOutcome.NotFound;
import com.kgtech.inventoryapi.inventory.StockOutcome.Ok;
import com.kgtech.inventoryapi.inventory.StockOutcome.Overflow;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.kgtech.inventoryapi.inventory.WriteResult.InvalidRequest;
import com.kgtech.inventoryapi.inventory.WriteResult.Stored;

/** The /v2 operations (OD-6): add stock, purchase, put details, read one, read a page. */
@Tag(name = TAG_V2)
@RestController
@RequestMapping(V2_BASE_PATH)
class SkuDetailsController {

    private final InventoryService service;
    private final OutcomeResponses outcomes;

    SkuDetailsController(InventoryService service, OutcomeResponses outcomes) {
        this.service = service;
        this.outcomes = outcomes;
    }

    @GetMapping(SKU_PATH)
    @Operation(operationId = "getSku", summary = V2_GET_SUMMARY)
    @ApiResponse(responseCode = "200", description = V2_GET_OK_DESCRIPTION,
            headers = @Header(name = ETAG, description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "404", description = SKU_NOT_FOUND_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> get(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId) {
        return service.findSku(skuId)
                .<ResponseEntity<?>>map(item -> withEtag(ResponseEntity.ok(), item))
                .orElseGet(TextErrors::skuNotFound);
    }

    @PostMapping(path = SKU_PATH, consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "addStock", summary = V2_ADD_SUMMARY, description = V2_ADD_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = V2_ADD_OK_DESCRIPTION,
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "400", description = CREATE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> add(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @Valid @RequestBody InventoryQuantity body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
                    description = V2_IDEMPOTENCY_KEY_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.addV2(skuId, body.quantity(), idempotencyKey));
    }

    @PostMapping(path = PURCHASE_PATH, consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "purchaseStock", summary = V2_PURCHASE_SUMMARY, description = V2_PURCHASE_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = V2_PURCHASE_OK_DESCRIPTION,
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "400", description = PURCHASE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "404", description = SKU_NOT_FOUND_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> purchase(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @Valid @RequestBody InventoryQuantity body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
                    description = V2_IDEMPOTENCY_KEY_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.purchaseV2(skuId, body.quantity(), idempotencyKey));
    }

    @PutMapping(path = DETAILS_PATH, consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "putSkuDetails", summary = PUT_DETAILS_SUMMARY, description = PUT_DETAILS_DESCRIPTION,
            parameters = {
                    @Parameter(name = IF_MATCH, in = ParameterIn.HEADER, required = false,
                            description = PUT_DETAILS_IF_MATCH_DESCRIPTION, schema = @Schema(type = "string")),
                    @Parameter(name = IF_NONE_MATCH, in = ParameterIn.HEADER, required = false,
                            description = IF_NONE_MATCH_DESCRIPTION, schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "200", description = PUT_DETAILS_REPLACED_DESCRIPTION,
            headers = @Header(name = ETAG, description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "201", description = PUT_DETAILS_CREATED_DESCRIPTION,
            headers = @Header(name = ETAG, description = PUT_CREATED_ETAG_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "400", description = CREATE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "412", description = V2_PRECONDITION_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> putDetails(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @RequestBody SkuDetails body,
            HttpServletRequest request) {
        Optional<DetailsPrecondition> precondition = IfMatch.parsePut(
                Collections.list(request.getHeaders(IF_MATCH)), Collections.list(request.getHeaders(IF_NONE_MATCH)));
        if (precondition.isEmpty()) {
            return TextErrors.invalidRequest();
        }
        return switch (service.putDetails(skuId, body, precondition.get())) {
            case PutResult.Created created -> withEtag(ResponseEntity.status(HttpStatus.CREATED), created.item());
            case PutResult.Replaced replaced -> withEtag(ResponseEntity.ok(), replaced.item());
            case PutResult.PreconditionFailed _ -> TextErrors.detailsChanged();
            case PutResult.InvalidRequest _ -> TextErrors.invalidRequest();
        };
    }

    @GetMapping
    @Operation(operationId = "listSkus", summary = V2_LIST_SUMMARY, description = V2_LIST_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = V2_LIST_OK_DESCRIPTION,
            headers = @Header(name = LINK, description = LINK_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = SkuItem.class))))
    @ApiResponse(responseCode = "400", description = LIST_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> list(
            @Parameter(description = LIMIT_DESCRIPTION,
                    schema = @Schema(type = "integer", minimum = "1", maximum = "" + MAX_LIMIT,
                            defaultValue = "" + DEFAULT_LIMIT))
            @RequestParam(name = LIMIT, required = false) String limit,
            @Parameter(description = V2_AFTER_DESCRIPTION,
                    schema = @Schema(type = "string"))
            @RequestParam(name = AFTER, required = false) String after,
            HttpServletRequest request) {
        if (Paging.afterRepeated(request)) {
            return TextErrors.invalidRequest(); // Z3
        }
        SkuPage page = service.listSkus(limit, after);
        return page.next()
                .<ResponseEntity<?>>map(next -> ResponseEntity.ok().header(LINK, Paging.nextLink(V2_BASE_PATH, next.limit(), next.after()))
                        .header(CACHE_CONTROL, NO_STORE).body(page.items()))
                .orElseGet(() -> ResponseEntity.ok().header(CACHE_CONTROL, NO_STORE).body(page.items()));
    }

    /** The ETag is a details validator for If-Match, not a cache key for the count: no store, no 304 (§8). */
    private static final String NO_STORE = "no-store";

    private static ResponseEntity<SkuItem> withEtag(ResponseEntity.BodyBuilder builder, SkuItem item) {
        return builder.header(ETAG, IfMatch.etag(item.detailsVersion())).header(CACHE_CONTROL, NO_STORE).body(item);
    }

    /**
     * One mapping for every write result, as on the spec routes: a stock outcome is rendered through the same
     * OutcomeResponses.toStored that a keyed request stores, so first and replayed responses are byte-identical (Y4).
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
