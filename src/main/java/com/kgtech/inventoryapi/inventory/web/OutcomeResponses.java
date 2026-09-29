package com.kgtech.inventoryapi.inventory.web;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.kgtech.inventoryapi.idempotency.StoredResponse;
import com.kgtech.inventoryapi.inventory.DetailsOutcome;
import com.kgtech.inventoryapi.inventory.DetailsOutcome.AlreadyExists;
import com.kgtech.inventoryapi.inventory.DetailsOutcome.Created;
import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.KeyedResponses;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.StockOutcome.Insufficient;
import com.kgtech.inventoryapi.inventory.StockOutcome.NotFound;
import com.kgtech.inventoryapi.inventory.StockOutcome.Ok;
import com.kgtech.inventoryapi.inventory.StockOutcome.Overflow;
import com.kgtech.inventoryapi.web.TextErrors;

import tools.jackson.databind.json.JsonMapper;

/**
 * How write outcomes become responses: the one rendering an unkeyed answer gets and a keyed one stores against its
 * Idempotency-Key (R1, U1, Y4, A28, A33), one exhaustive switch per outcome family (A38).
 */
@Component
final class OutcomeResponses implements KeyedResponses {

    private final JsonMapper json;

    OutcomeResponses(JsonMapper json) {
        this.json = json;
    }

    /** Y4, R1, U1: exactly the status, Content-Type and body the unkeyed stock write sends. */
    @Override
    public StoredResponse toStored(String skuId, StockOutcome outcome) {
        return switch (outcome) {
            case Ok ok -> new StoredResponse(200, APPLICATION_JSON_VALUE,
                    json.writeValueAsString(new InventoryItem(skuId, ok.quantity())));
            case NotFound _ -> text(TextErrors.skuNotFound());
            case Insufficient _ -> text(TextErrors.insufficientInventory());
            case Overflow _ -> text(TextErrors.invalidRequest());
        };
    }

    /** Y4, A28: exactly the status, Content-Type and body the unkeyed create sends (its ETag is the controller's). */
    @Override
    public StoredResponse toStored(String skuId, DetailsOutcome outcome) {
        return switch (outcome) {
            case Created created -> new StoredResponse(201, APPLICATION_JSON_VALUE,
                    json.writeValueAsString(created.item()));
            case AlreadyExists _ -> text(TextErrors.skuExists());
        };
    }

    /** The same status, Content-Type and body the unkeyed path sends (S5). */
    private static StoredResponse text(ResponseEntity<String> error) {
        return new StoredResponse(error.getStatusCode().value(), String.valueOf(error.getHeaders().getContentType()),
                error.getBody());
    }
}
