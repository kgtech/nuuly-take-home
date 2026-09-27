package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * Y4, issue #15 (R3-1): the web layer renders a StoredResponse; the idempotency record carries no Spring HTTP types.
 * Status, Content-Type and body are sent exactly as stored. Plain unit test.
 */
class StoredResponsesTest {

    static Stream<Arguments> rendersStoredResponseUnchanged() {
        return Stream.of(
                Arguments.of(200, MediaType.APPLICATION_JSON_VALUE, "{\"skuId\":\"widget\",\"quantity\":5}"),
                Arguments.of(200, MediaType.APPLICATION_JSON_VALUE, "{\"skuId\":\"AbC-1\",\"quantity\":0}"),
                Arguments.of(404, MediaType.TEXT_PLAIN_VALUE, "SKU not found"),
                Arguments.of(400, MediaType.TEXT_PLAIN_VALUE, "Insufficient inventory"),
                Arguments.of(400, MediaType.TEXT_PLAIN_VALUE, "Invalid request"));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource
    void rendersStoredResponseUnchanged(int status, String contentType, String body) {
        ResponseEntity<String> rendered =
                StoredResponses.toResponseEntity(new StoredResponse(status, contentType, body));

        assertThat(rendered.getStatusCode().value()).isEqualTo(status);
        assertThat(rendered.getHeaders().get(CONTENT_TYPE)).containsExactly(contentType);
        assertThat(rendered.getBody()).isEqualTo(body);
    }
}
