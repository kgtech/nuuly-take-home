package com.kgtech.inventoryapi.inventory;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

import tools.jackson.databind.json.JsonMapper;

/** Renders a stock outcome as the response stored against an Idempotency-Key (R1, U1, Y4). */
@Component
final class OutcomeResponses {

    private final JsonMapper json;

    OutcomeResponses(JsonMapper json) {
        this.json = json;
    }

    StoredResponse render(String skuId, StockOutcome outcome) {
        return switch (outcome) {
            case StockOutcome.Ok ok -> new StoredResponse(200, MediaType.APPLICATION_JSON_VALUE,
                    json.writeValueAsString(new InventoryItem(skuId, ok.quantity())));
            case StockOutcome.NotFound _ -> text(TextErrors.skuNotFound());
            case StockOutcome.Insufficient _ -> text(TextErrors.insufficientInventory());
            case StockOutcome.Overflow _ -> text(TextErrors.invalidRequest());
        };
    }

    /** The same status, Content-Type and body the unkeyed path sends (S5). */
    private static StoredResponse text(ResponseEntity<String> error) {
        return new StoredResponse(error.getStatusCode().value(), String.valueOf(error.getHeaders().getContentType()),
                error.getBody());
    }
}
