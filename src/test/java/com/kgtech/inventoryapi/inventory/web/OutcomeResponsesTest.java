package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import com.kgtech.inventoryapi.idempotency.StoredResponse;
import com.kgtech.inventoryapi.inventory.DetailsOutcome;
import com.kgtech.inventoryapi.inventory.SkuCost;
import com.kgtech.inventoryapi.inventory.SkuDetails;
import com.kgtech.inventoryapi.inventory.SkuItem;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.web.TextErrors;

import tools.jackson.databind.json.JsonMapper;

/**
 * R1, U1, Y4, A33: OutcomeResponses is the KeyedResponses the service stores against a key; toStored renders exactly
 * what the unkeyed path sends. Plain unit test.
 */
class OutcomeResponsesTest {

    private final OutcomeResponses responses = new OutcomeResponses(JsonMapper.builder().build());

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

    static Stream<Arguments> toStoredMatchesUnkeyedCreateResponse() {
        SkuDetails details = new SkuDetails("Linen shirt", "Long sleeve", Optional.of(new SkuCost(12900, "USD")),
                List.of("https://cdn.example.com/a.jpg"));
        return Stream.of(
                Arguments.of(new DetailsOutcome.Created(new SkuItem("widget", 5, Optional.of(details), 1)), 201,
                        "application/json", "{\"skuId\":\"widget\",\"quantity\":5,\"details\":{"
                                + "\"name\":\"Linen shirt\",\"description\":\"Long sleeve\","
                                + "\"cost\":{\"amount\":12900,\"currency\":\"USD\"},"
                                + "\"images\":[\"https://cdn.example.com/a.jpg\"]}}"),
                Arguments.of(new DetailsOutcome.Created(new SkuItem("widget", 0, Optional.empty(), 0)), 201,
                        "application/json", "{\"skuId\":\"widget\",\"quantity\":0}"),
                Arguments.of(new DetailsOutcome.AlreadyExists(), 409, "text/plain", TextErrors.SKU_EXISTS));
    }

    /**
     * Y4, A28, A38: the v2 create's 201 (the item, without the ETag's details version) and 409 are stored exactly as
     * the unkeyed create sends them; the first details version rides in the ETag, not the body.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void toStoredMatchesUnkeyedCreateResponse(DetailsOutcome outcome, int status, String contentType, String body) {
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
        ResponseEntity<String> rendered = StoredResponses.toResponseEntity(stored);
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
}
