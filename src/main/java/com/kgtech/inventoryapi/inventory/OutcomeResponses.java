package com.kgtech.inventoryapi.inventory;

import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.kgtech.inventoryapi.idempotency.IdempotentResults;
import com.kgtech.inventoryapi.idempotency.Operation;
import com.kgtech.inventoryapi.idempotency.StoredResponse;

import tools.jackson.databind.json.JsonMapper;

/** How stock-write results are checked, stored against an Idempotency-Key and rebuilt (R1, U1, Y4, Z1). */
@Component
final class OutcomeResponses implements IdempotentResults<WriteResult> {

    private final JsonMapper json;

    OutcomeResponses(JsonMapper json) {
        this.json = json;
    }

    /** S2, U3: the same skuId check the service body runs; the rejection is not stored. */
    @Override
    public Optional<WriteResult> beforeClaim(Operation operation, String skuId) {
        return SkuId.rejection(operation, skuId);
    }

    /** Y4, R1, U1: exactly the status, Content-Type and body the unkeyed path sends. */
    @Override
    public StoredResponse toStored(String skuId, WriteResult result) {
        return switch (result) {
            case StockOutcome.Ok ok -> new StoredResponse(200, MediaType.APPLICATION_JSON_VALUE,
                    json.writeValueAsString(new InventoryItem(skuId, ok.quantity())));
            case StockOutcome.NotFound _ -> text(TextErrors.skuNotFound());
            case StockOutcome.Insufficient _ -> text(TextErrors.insufficientInventory());
            case StockOutcome.Overflow _ -> text(TextErrors.invalidRequest());
            case WriteResult.Stored _, WriteResult.InvalidRequest _ ->
                    throw new IllegalStateException("not a stock outcome: " + result);
        };
    }

    @Override
    public WriteResult stored(StoredResponse response) {
        return new WriteResult.Stored(response);
    }

    @Override
    public WriteResult invalidRequest() {
        return new WriteResult.InvalidRequest();
    }

    /** The same status, Content-Type and body the unkeyed path sends (S5). */
    private static StoredResponse text(ResponseEntity<String> error) {
        return new StoredResponse(error.getStatusCode().value(), String.valueOf(error.getHeaders().getContentType()),
                error.getBody());
    }
}
