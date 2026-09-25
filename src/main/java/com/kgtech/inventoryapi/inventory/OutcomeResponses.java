package com.kgtech.inventoryapi.inventory;

import java.util.Optional;

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

    @Override
    public Optional<WriteResult> beforeClaim(Operation operation, String skuId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public StoredResponse toStored(String skuId, WriteResult result) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WriteResult stored(StoredResponse response) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WriteResult invalidRequest() {
        throw new UnsupportedOperationException("not implemented");
    }
}
