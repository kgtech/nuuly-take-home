package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.RawHttp.header;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.HttpHeaders.TRANSFER_ENCODING;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.RawHttp;
import com.kgtech.inventoryapi.Tables;

/**
 * M-02 through real Tomcat (MockMvc has no /error dispatch): Boot's error controller is not an API. A direct request
 * to the exact path /error answers 404 text/plain "Not Found" (the standard-code rule of G10, T3), and a malformed
 * chunk framing on any write route answers 400 text/plain "Invalid request", never Boot's JSON or HTML body.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorPathIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "A1", 5);
    }

    private static void assertTextPlain(RawHttp.Response response, int status, String body) {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.contentType()).as(response.toString()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(MediaType.TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo(body);
    }

    // ---- a direct request to /error ----

    static Stream<Arguments> errorRequests() {
        return Stream.of("GET", "POST", "PUT", "DELETE").flatMap(method -> Stream.of("text/html", "application/json",
                "*/*").map(accept -> Arguments.of(method, accept)));
    }

    @ParameterizedTest(name = "{0} /error accept={1}")
    @MethodSource("errorRequests")
    void aDirectRequestToErrorIs404TextPlain(String method, String accept) throws Exception {
        String head = method + " /error HTTP/1.1\r\n" + header(HOST, "localhost") + header(CONNECTION, "close")
                + header(ACCEPT, accept) + header(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                + header(CONTENT_LENGTH, "0");

        assertTextPlain(RawHttp.send(port, head, ""), 404, "Not Found");
    }

    /** HEAD has no body: status and Content-Type only. */
    @ParameterizedTest(name = "HEAD /error accept={0}")
    @ValueSource(strings = {"text/html", "application/json"})
    void aHeadRequestToErrorIs404TextPlain(String accept) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error"))
                    .header(ACCEPT, accept).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue(CONTENT_TYPE).orElse("")).startsWith(MediaType.TEXT_PLAIN_VALUE);
        }
    }

    // ---- a malformed chunk framing on a write route ----

    /** One row per write route of both versions: request line, body prefix, key header needed. */
    static Stream<Arguments> writeRoutes() {
        return Stream.of(
                Arguments.of("POST /inventory/A1", false),
                Arguments.of("POST /inventory/A1/purchase", false),
                Arguments.of("POST /v2/inventory/A1", true),
                Arguments.of("POST /v2/inventory/A1/purchase", true),
                Arguments.of("PUT /v2/inventory/A1/details", false));
    }

    /** A chunk-size line that is not hexadecimal ("zz") fails while Tomcat reads the body, before any handler runs. */
    @ParameterizedTest(name = "{0}, chunk size zz")
    @MethodSource("writeRoutes")
    void aBadChunkSizeLineIs400TextPlainAndWritesNothing(String requestLine, boolean keyed) throws Exception {
        String head = requestLine + " HTTP/1.1\r\n" + header(HOST, "localhost") + header(CONNECTION, "close")
                + header(ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                + header(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE) + header(TRANSFER_ENCODING, "chunked")
                + (keyed ? header(IDEMPOTENCY_KEY, UUID.randomUUID().toString()) : "");
        Map<String, Long> before = Tables.counts(jdbc);

        RawHttp.Response response = RawHttp.send(port, head, "zz\r\n{\"quantity\":1}\r\n0\r\n\r\n");

        assertTextPlain(response, 400, "Invalid request");
        assertThat(Tables.counts(jdbc)).isEqualTo(before);
    }
}
