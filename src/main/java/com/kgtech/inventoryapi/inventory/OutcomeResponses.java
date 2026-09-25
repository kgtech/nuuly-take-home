package com.kgtech.inventoryapi.inventory;

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
        throw new UnsupportedOperationException("not implemented");
    }
}
