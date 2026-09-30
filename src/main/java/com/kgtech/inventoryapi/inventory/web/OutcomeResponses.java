package com.kgtech.inventoryapi.inventory.web;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.kgtech.inventoryapi.idempotency.StoredResponse;
import com.kgtech.inventoryapi.inventory.KeyedResponses;
import com.kgtech.inventoryapi.inventory.SkuItem;
import com.kgtech.inventoryapi.inventory.StockOutcome.Insufficient;
import com.kgtech.inventoryapi.inventory.StockOutcome.NotFound;
import com.kgtech.inventoryapi.inventory.StockOutcome.Ok;
import com.kgtech.inventoryapi.inventory.StockOutcome.Overflow;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.kgtech.inventoryapi.inventory.WriteResult.InvalidRequest;
import com.kgtech.inventoryapi.inventory.WriteResult.Stored;

import tools.jackson.databind.json.JsonMapper;

/**
 * How write outcomes become responses: the one rendering an unkeyed answer gets and a keyed one stores against its
 * Idempotency-Key (R1, U1, Y4, A33). An Ok is rendered as a SkuItem: without details (every spec response, and a /v2
 * SKU that has none) that is exactly the spec's {skuId, quantity}; a /v2 response adds the details (H6).
 */
@Component
final class OutcomeResponses implements KeyedResponses {

    private final JsonMapper json;

    OutcomeResponses(JsonMapper json) {
        this.json = json;
    }

    /** Y4, R1, U1: exactly the status, Content-Type and body the unkeyed path sends. */
    @Override
    public StoredResponse toStored(String skuId, WriteResult result) {
        return switch (result) {
            case Ok ok -> new StoredResponse(200, APPLICATION_JSON_VALUE,
                    json.writeValueAsString(new SkuItem(skuId, ok.quantity(), ok.details(), 0)));
            case NotFound _ -> text(TextErrors.skuNotFound());
            case Insufficient _ -> text(TextErrors.insufficientInventory());
            case Overflow _ -> text(TextErrors.invalidRequest());
            case Stored _, InvalidRequest _ ->
                    throw new IllegalStateException("not a stock outcome: " + result);
        };
    }

    /** The same status, Content-Type and body the unkeyed path sends (S5). */
    private static StoredResponse text(ResponseEntity<String> error) {
        return new StoredResponse(error.getStatusCode().value(), String.valueOf(error.getHeaders().getContentType()),
                error.getBody());
    }
}
