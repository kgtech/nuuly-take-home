package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import com.kgtech.inventoryapi.idempotency.Operation;
import com.kgtech.inventoryapi.idempotency.StoredResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Z1, S2, R1, U1, Y4: OutcomeResponses is the IdempotentResults for stock writes. beforeClaim applies the skuId check
 * (create 400, purchase 404); toStored renders exactly what the unkeyed path sends; stored and invalidRequest build
 * the outcome values the controller maps. Plain unit test.
 */
class OutcomeResponsesTest {

    private final OutcomeResponses responses = new OutcomeResponses(JsonMapper.builder().build());

    static Stream<String> malformedSkuIds() {
        return Stream.of("-bad", "", "a".repeat(65), "a b", "abc\n");
    }

    @ParameterizedTest
    @MethodSource("malformedSkuIds")
    void beforeClaimRejectsMalformedSkuIdOnCreateAsInvalidRequest(String skuId) {
        assertThat(responses.beforeClaim(Operation.ADD, skuId)).contains(new WriteResult.InvalidRequest());
    }

    @ParameterizedTest
    @MethodSource("malformedSkuIds")
    void beforeClaimRejectsMalformedSkuIdOnPurchaseAsNotFound(String skuId) {
        assertThat(responses.beforeClaim(Operation.PURCHASE, skuId)).contains(new StockOutcome.NotFound());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void beforeClaimRejectsNullSkuId(Operation operation) {
        assertThat(responses.beforeClaim(operation, null)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void beforeClaimPassesValidSkuId(Operation operation) {
        for (String skuId : Arrays.asList("widget", "CW-XYCS-BM-01", "a".repeat(64))) {
            assertThat(responses.beforeClaim(operation, skuId)).as(skuId).isEqualTo(Optional.empty());
        }
    }

    static Stream<Arguments> toStoredMatchesUnkeyedResponse() {
        return Stream.of(
                Arguments.of(new StockOutcome.Ok(5), 200, "application/json", "{\"skuId\":\"widget\",\"quantity\":5}"),
                Arguments.of(new StockOutcome.Ok(0), 200, "application/json", "{\"skuId\":\"widget\",\"quantity\":0}"),
                Arguments.of(new StockOutcome.Ok(Long.MAX_VALUE), 200, "application/json",
                        "{\"skuId\":\"widget\",\"quantity\":9223372036854775807}"),
                Arguments.of(new StockOutcome.NotFound(), 404, "text/plain", "SKU not found"),
                Arguments.of(new StockOutcome.Insufficient(), 400, "text/plain", "Insufficient inventory"),
                Arguments.of(new StockOutcome.Overflow(), 400, "text/plain", "Invalid request"));
    }

    /** Y4, R1, U1: 200, 404, 400 insufficient and the overflow 400 are stored with their Content-Type. */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void toStoredMatchesUnkeyedResponse(StockOutcome outcome, int status, String contentType, String body) {
        assertThat(responses.toStored("widget", outcome)).isEqualTo(new StoredResponse(status, contentType, body));
    }

    /** The stored text errors are the TextErrors responses the controller sends without a key (S5). */
    @Test
    void storedTextErrorsMatchTextErrors() {
        assertStoredEquals(responses.toStored("widget", new StockOutcome.NotFound()), TextErrors.skuNotFound());
        assertStoredEquals(responses.toStored("widget", new StockOutcome.Insufficient()),
                TextErrors.insufficientInventory());
        assertStoredEquals(responses.toStored("widget", new StockOutcome.Overflow()), TextErrors.invalidRequest());
    }

    private static void assertStoredEquals(StoredResponse stored, ResponseEntity<String> expected) {
        ResponseEntity<String> rendered = stored.toResponseEntity();
        assertThat(rendered.getStatusCode()).isEqualTo(expected.getStatusCode());
        assertThat(rendered.getHeaders().getContentType()).isEqualTo(expected.getHeaders().getContentType());
        assertThat(rendered.getBody()).isEqualTo(expected.getBody());
    }

    /** G1: the stored body carries the skuId it was given, case unchanged. */
    @Test
    void toStoredUsesGivenSkuId() {
        assertThat(responses.toStored("AbC-1", new StockOutcome.Ok(3)).body())
                .isEqualTo("{\"skuId\":\"AbC-1\",\"quantity\":3}");
    }

    @Test
    void storedWrapsResponse() {
        StoredResponse response = new StoredResponse(404, "text/plain", "SKU not found");

        assertThat(responses.stored(response)).isEqualTo(new WriteResult.Stored(response));
    }

    @Test
    void invalidRequestIsInvalidRequestOutcome() {
        assertThat(responses.invalidRequest()).isEqualTo(new WriteResult.InvalidRequest());
    }
}
