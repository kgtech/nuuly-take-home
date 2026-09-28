package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.InventoryService.DEFAULT_LIMIT;
import static com.kgtech.inventoryapi.inventory.InventoryService.MAX_LIMIT;
import static com.kgtech.inventoryapi.inventory.SkuId.MAX_LENGTH;
import static com.kgtech.inventoryapi.inventory.SkuId.PATTERN_REGEX;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.AFTER_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.CREATE_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.CREATED_ETAG_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.ETAG_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.IDEMPOTENCY_KEY_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.IF_MATCH_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIMIT_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LINK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.LIST_INVALID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_ID_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_NOT_FOUND_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.SKU_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.TAG_V2;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_BASE_PATH;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_CONFLICT_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_CREATED_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_CREATE_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_CREATE_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_GET_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_GET_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_LIST_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_LIST_OK_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_LIST_SUMMARY;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_PRECONDITION_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_REPLACED_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_REPLACE_DESCRIPTION;
import static com.kgtech.inventoryapi.inventory.web.InventoryApi.V2_REPLACE_SUMMARY;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.kgtech.inventoryapi.idempotency.StoredResponse;
import com.kgtech.inventoryapi.inventory.CreateSku;
import com.kgtech.inventoryapi.inventory.DetailsOutcome.AlreadyExists;
import com.kgtech.inventoryapi.inventory.DetailsOutcome.Created;
import com.kgtech.inventoryapi.inventory.DetailsPrecondition;
import com.kgtech.inventoryapi.inventory.InventoryPage.Next;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.ReplaceResult;
import com.kgtech.inventoryapi.inventory.ReplaceResult.NotFound;
import com.kgtech.inventoryapi.inventory.ReplaceResult.Replaced;
import com.kgtech.inventoryapi.inventory.ReplaceResult.VersionMismatch;
import com.kgtech.inventoryapi.inventory.SkuDetails;
import com.kgtech.inventoryapi.inventory.SkuItem;
import com.kgtech.inventoryapi.inventory.SkuPage;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.kgtech.inventoryapi.inventory.WriteResult.InvalidRequest;
import com.kgtech.inventoryapi.inventory.WriteResult.Stored;

/** The v2 details operations (DESIGN-V2 §8, A21): create with details, replace details, read one, read a page. */
@Tag(name = TAG_V2)
@RestController
@RequestMapping(V2_BASE_PATH)
class SkuDetailsController {

    /** A first or replayed 201 always describes details version 1 (§8 "Create"). */
    private static final String CREATED_ETAG = IfMatch.etag(1);

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
    @Operation(operationId = "createSku", summary = V2_CREATE_SUMMARY, description = V2_CREATE_DESCRIPTION)
    @ApiResponse(responseCode = "201", description = V2_CREATED_DESCRIPTION,
            headers = @Header(name = ETAG, description = CREATED_ETAG_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "400", description = CREATE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "409", description = V2_CONFLICT_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> create(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @RequestBody CreateSku body,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = false,
                    description = IDEMPOTENCY_KEY_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return toResponse(skuId, service.create(skuId, body, idempotencyKey));
    }

    @PutMapping(path = SKU_PATH, consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    @Operation(operationId = "replaceSkuDetails", summary = V2_REPLACE_SUMMARY, description = V2_REPLACE_DESCRIPTION,
            parameters = @Parameter(name = IF_MATCH, in = ParameterIn.HEADER, required = false,
                    description = IF_MATCH_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "200", description = V2_REPLACED_DESCRIPTION,
            headers = @Header(name = ETAG, description = ETAG_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = APPLICATION_JSON_VALUE, schema = @Schema(implementation = SkuItem.class)))
    @ApiResponse(responseCode = "400", description = CREATE_INVALID_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "404", description = SKU_NOT_FOUND_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "412", description = V2_PRECONDITION_DESCRIPTION,
            content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(implementation = String.class)))
    ResponseEntity<?> replace(
            @Parameter(description = SKU_ID_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = PATTERN_REGEX, minLength = 1, maxLength = MAX_LENGTH))
            @PathVariable String skuId,
            @RequestBody SkuDetails body,
            HttpServletRequest request) {
        // Every If-Match header line counts (RFC 9110 allows a list across lines), so the raw headers are parsed.
        Optional<DetailsPrecondition> precondition =
                IfMatch.parse(Collections.list(request.getHeaders(IF_MATCH)));
        if (precondition.isEmpty()) {
            return TextErrors.invalidRequest();
        }
        ReplaceResult result = service.replaceDetails(skuId, body, precondition.get());
        return switch (result) {
            case Replaced replaced -> withEtag(ResponseEntity.ok(), replaced.item());
            case NotFound _ -> TextErrors.skuNotFound();
            case VersionMismatch _ -> TextErrors.detailsChanged();
            case ReplaceResult.InvalidRequest _ -> TextErrors.invalidRequest();
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
            @Parameter(description = AFTER_DESCRIPTION,
                    schema = @Schema(type = "string"))
            @RequestParam(name = AFTER, required = false) String after,
            HttpServletRequest request) {
        String[] afters = request.getParameterValues(AFTER);
        if (afters != null && afters.length > 1) {
            return TextErrors.invalidRequest(); // Z3
        }
        SkuPage page = service.listSkus(limit, after);
        return page.next()
                .<ResponseEntity<?>>map(next -> ResponseEntity.ok().header(LINK, nextLink(next))
                        .header(CACHE_CONTROL, NO_STORE).body(page.items()))
                .orElseGet(() -> ResponseEntity.ok().header(CACHE_CONTROL, NO_STORE).body(page.items()));
    }

    /** C2 for v2: the Link is built from the request's origin plus the routed base path. */
    private static String nextLink(Next next) {
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(V2_BASE_PATH)
                .queryParam(LIMIT, next.limit())
                .queryParam(AFTER, "{after}")
                .encode()
                .buildAndExpand(next.after())
                .toUriString();
        return "<" + url + ">; rel=\"next\"";
    }

    /** The ETag is a details validator for If-Match, not a cache key for the count: no store, no 304 (§8). */
    private static final String NO_STORE = "no-store";

    private static ResponseEntity<SkuItem> withEtag(ResponseEntity.BodyBuilder builder, SkuItem item) {
        return builder.header(ETAG, IfMatch.etag(item.detailsVersion())).header(CACHE_CONTROL, NO_STORE).body(item);
    }

    /**
     * One mapping for every create result: a details outcome is rendered through the same OutcomeResponses.toStored a
     * keyed request stores, so unkeyed and replayed responses are byte-identical (Y4); a 201 carries the ETag of
     * version 1 whether first or replayed.
     */
    private ResponseEntity<?> toResponse(String skuId, WriteResult result) {
        return switch (result) {
            case Stored stored -> render(stored.response());
            case InvalidRequest _ -> TextErrors.invalidRequest();
            case Created _, AlreadyExists _ -> render(outcomes.toStored(skuId, result));
            case StockOutcome _ -> throw new IllegalStateException("not a create outcome: " + result);
        };
    }

    private static ResponseEntity<String> render(StoredResponse response) {
        ResponseEntity<String> entity = StoredResponses.toResponseEntity(response);
        if (response.status() != HttpStatus.CREATED.value()) {
            return entity;
        }
        return ResponseEntity.status(HttpStatus.CREATED).headers(entity.getHeaders()).header(ETAG, CREATED_ETAG)
                .header(CACHE_CONTROL, NO_STORE).body(response.body());
    }
}
