package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.IntegrationTest;

/** Issue #23 (C-04, C-34): a raw ';' in the SKU segment and an Accept that excludes JSON write nothing. */
@IntegrationTest
@AutoConfigureMockMvc
class InventoryRequestGuardIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "ABC-1", 5);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder jsonPost(String path) {
        return jsonPost(path, APPLICATION_JSON.toString());
    }

    /** One Accept header exactly: the value under test. */
    private static MockHttpServletRequestBuilder jsonPost(String path, String accept) {
        return post(path).contentType(APPLICATION_JSON).header(HttpHeaders.ACCEPT, accept).content("{\"quantity\":1}")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString());
    }

    static Stream<Arguments> matrixSegments() {
        return Stream.of(
                Arguments.of("create", jsonPost("/inventory/ABC-1;lot=7"), 400, "Invalid request"),
                Arguments.of("purchase", jsonPost("/inventory/ABC-1;x/purchase"), 404, "SKU not found"),
                Arguments.of("get", get("/inventory/ABC-1;x=y").accept(APPLICATION_JSON), 404, "SKU not found"),
                Arguments.of("purchase, matrix on the base segment", jsonPost("/inventory;v=1/ABC-1;x/purchase"), 404,
                        "SKU not found"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrixSegments")
    void rawSemicolonNeverReachesAnotherSku(String what, MockHttpServletRequestBuilder request, int status, String body)
            throws Exception {
        MockHttpServletResponse response = send(request);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).isEqualTo(body);
        assertNothingWritten();
    }

    static Stream<Arguments> jsonExcluded() {
        return Stream.of(
                Arguments.of("q=0", "application/json;q=0"),
                Arguments.of("q=0 with another type", "application/json;q=0, application/xml"),
                Arguments.of("xml only", "application/xml"),
                Arguments.of("json refused, wildcard accepted (RFC 9110: the specific range wins)",
                        "application/json;q=0, */*;q=0.1"),
                Arguments.of("same, other order", "*/*;q=0.1, application/json;q=0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonExcluded")
    void postWhoseAcceptExcludesJsonIs400AndWritesNothing(String what, String accept) throws Exception {
        for (String path : new String[] {"/inventory/ABC-1", "/inventory/ABC-1/purchase"}) {
            MockHttpServletResponse response = send(jsonPost(path, accept));

            assertThat(response.getStatus()).as(path).isEqualTo(400);
            assertThat(response.getContentType()).startsWith("text/plain");
            assertThat(response.getContentAsString()).isEqualTo("Invalid request");
        }
        assertNothingWritten();
    }

    @Test
    void wildcardOrPositiveJsonQualityIsAccepted() throws Exception {
        assertThat(send(jsonPost("/inventory/ABC-1", "*/*")).getStatus()).isEqualTo(200);
        assertThat(send(jsonPost("/inventory/ABC-1", "application/*;q=0.5")).getStatus()).isEqualTo(200);
        assertThat(send(jsonPost("/inventory/ABC-1", "application/json;q=0.5")).getStatus()).isEqualTo(200);
        assertThat(send(jsonPost("/inventory/ABC-1", "text/plain, application/json")).getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'ABC-1'").query(Long.class).single()).isEqualTo(9);
    }

    /** All Accept lines count: a refusal on a second line still refuses. */
    @Test
    void secondAcceptLineIsHonoured() throws Exception {
        MockHttpServletResponse response = send(jsonPost("/inventory/ABC-1", "*/*").header(HttpHeaders.ACCEPT,
                "application/json;q=0"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        assertThat(jdbc.sql("SELECT quantity, version FROM sku WHERE sku_id = 'ABC-1'").query().singleRow())
                .containsEntry("quantity", 5L).containsEntry("version", 1L);
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single()).isZero();
    }
}
