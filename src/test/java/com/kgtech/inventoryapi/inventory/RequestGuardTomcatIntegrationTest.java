package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.RawHttp.header;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.HttpHeaders.TRANSFER_ENCODING;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.RawHttp;
import com.kgtech.inventoryapi.Tables;

/**
 * The request guard through real Tomcat (C1: MockMvc does not decode the path): a percent-encoded prefix or matrix
 * content on the base segment still reaches the guard, because it works on the routed path Spring matches on.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestGuardTomcatIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "ABC-1", 5);
    }

    private static final String QUANTITY_JSON = "{\"quantity\":1}";

    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("POST /%69nventory/ABC-1;lot=7", "{\"quantity\":5}", null, 400, "Invalid request"),
                Arguments.of("GET /%69nventory/ABC-1;x=y", "", null, 404, "SKU not found"),
                Arguments.of("POST /inventory;v=1/ABC-1;x/purchase", "{\"quantity\":1}", null, 404, "SKU not found"),
                Arguments.of("POST /%69nventory/ABC-1", "{\"quantity\":1}", "application/json;q=0", 400, "Invalid request"),
                Arguments.of("POST /%69nventory/ABC-1", "{\"quantity\":1}", "application/json;q=0, */*;q=0.1", 400,
                        "Invalid request"),
                // v2 (DESIGN-V2 §8, review R-06a): the same guard on the routed path
                Arguments.of("GET /v2/inventory/ABC-1%3Blot=7", "", null, 404, "SKU not found"),
                Arguments.of("GET /v2/inventory/A%2FB", "", null, 404, "SKU not found"),
                Arguments.of("POST /v2/inventory/ABC-1%3Blot=7", QUANTITY_JSON, null, 400, "Invalid request"),
                Arguments.of("POST /v2/inventory/A%2FB", QUANTITY_JSON, null, 400, "Invalid request"),
                Arguments.of("PUT /v2/%69nventory/A%2FB/details", "{\"name\":\"n\"}", null, 400, "Invalid request"),
                Arguments.of("POST /v2/inventory/NEW-9", QUANTITY_JSON, "application/json;q=0", 400, "Invalid request"),
                // F-04: purchase and details are guarded like the other v2 writes
                Arguments.of("POST /v2/inventory/ABC-1%3Bx/purchase", "{\"quantity\":1}", null, 404, "SKU not found"),
                Arguments.of("POST /v2;a=b/%69nventory/ABC-1;x/purchase;y", "{\"quantity\":1}", null, 404,
                        "SKU not found"),
                Arguments.of("PUT /v2/inventory/ABC-1;x/details", "{\"name\":\"n\"}", null, 400, "Invalid request"),
                Arguments.of("PUT /v2/inventory/ABC-1%3Bx/details", "{\"name\":\"n\"}", null, 400, "Invalid request"),
                Arguments.of("POST /v2/inventory/ABC-1/purchase", "{\"quantity\":1}", "application/json;q=0", 400,
                        "Invalid request"),
                Arguments.of("PUT /v2/inventory/ABC-1/details", "{\"name\":\"n\"}", "text/html", 400, "Invalid request"),
                Arguments.of("POST /v2/inventory/ABC-1", QUANTITY_JSON, "text/html", 400, "Invalid request"));
    }

    @ParameterizedTest(name = "{0} accept={2}")
    @MethodSource("requests")
    void guardWorksOnTheRoutedPath(String requestLine, String body, String accept, int status, String text)
            throws Exception {
        String head = requestLine + " HTTP/1.1\r\n" + header(HOST, "localhost") + header(CONNECTION, "close")
                + header(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                + header(CONTENT_LENGTH, Integer.toString(body.getBytes().length))
                + (accept == null ? "" : header(ACCEPT, accept));

        RawHttp.Response response = RawHttp.send(port, head, body);

        assertThat(response.status()).isEqualTo(status);
        assertThat(response.contentType()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
        assertThat(response.body()).isEqualTo(text);
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'ABC-1'").query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isEqualTo(1);
    }

    private static final String QUANTITY_BODY = "{\"quantity\":1";
    private static final String DETAILS_BODY = "{\"name\":\"n\"";

    static Stream<Arguments> chunkedBodies() {
        return Stream.of(
                Arguments.of("POST /v2/inventory/CH-1", QUANTITY_BODY, 2_000, 200),
                Arguments.of("POST /v2/inventory/CH-1", QUANTITY_BODY, 5_000, 400),
                Arguments.of("POST /v2/inventory/CH-1", QUANTITY_BODY, 70_000, 400),
                Arguments.of("POST /v2/inventory/ABC-1/purchase", QUANTITY_BODY, 5_000, 400),
                Arguments.of("PUT /v2/inventory/CH-1/details", DETAILS_BODY, 40_000, 201),
                Arguments.of("PUT /v2/inventory/CH-1/details", DETAILS_BODY, 70_000, 400),
                // frozen, not changed (A19): an unversioned chunked body is not capped while read
                Arguments.of("POST /inventory/CH-1", QUANTITY_BODY, 70_000, 200));
    }

    /**
     * Review R-01, F-04: a chunked body (no Content-Length) is counted as it is read on every v2 write route: 4096 bytes
     * on the two POSTs, 65,536 on the details PUT; one within the cap is accepted.
     */
    @ParameterizedTest(name = "chunked {0} of about {2} bytes → {3}")
    @MethodSource("chunkedBodies")
    void chunkedBodyIsCappedWhileRead(String requestLine, String bodyPrefix, int padBytes, int status)
            throws Exception {
        // The size comes from an ignored property (G13), so only the byte cap can reject the smaller body.
        String body = bodyPrefix + ",\"pad\":\"" + "d".repeat(padBytes) + "\"}";
        String chunked = Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
        String head = requestLine + " HTTP/1.1\r\n" + header(HOST, "localhost") + header(CONNECTION, "close")
                + header(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                + header(TRANSFER_ENCODING, "chunked")
                // the /v2 routes take a key; the unversioned POST rejects the header (H3)
                + (requestLine.contains(" /v2/") ? header(IDEMPOTENCY_KEY, UUID.randomUUID().toString()) : "");
        java.util.Map<String, Long> before = Tables.counts(jdbc);

        RawHttp.Response response = RawHttp.send(port, head, chunked);

        assertThat(response.status()).isEqualTo(status);
        if (status == 400) {
            assertThat(response.contentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
            assertThat(response.body()).isEqualTo("Invalid request");
            assertThat(Tables.counts(jdbc)).isEqualTo(before);
        } else {
            // the add creates CH-1 with 1; the details PUT creates it at 0
            assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'CH-1'").query(Long.class).single())
                    .isEqualTo(requestLine.contains("/details") ? 0 : 1);
        }
    }

    /** HEAD is served through the GET handler, so the guard treats it like GET (critique M-14): 404, no body. */
    @org.junit.jupiter.api.Test
    void headWithMatrixSegmentIsGuardedLikeGet() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/inventory/ABC-1;x"))
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build();

        java.net.http.HttpResponse<String> response =
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue(CONTENT_TYPE).orElse("")).startsWith("text/plain");
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'ABC-1'").query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isEqualTo(1);

        // The property the guard relies on: a clean HEAD still goes through the GET handler.
        java.net.http.HttpResponse<String> clean = client.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/inventory/ABC-1"))
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(clean.statusCode()).isEqualTo(200);
        assertThat(clean.headers().firstValue(CONTENT_TYPE).orElse("")).startsWith("application/json");
    }
}
