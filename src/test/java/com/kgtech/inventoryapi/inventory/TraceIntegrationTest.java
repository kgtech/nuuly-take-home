package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.RawHttp;
import com.kgtech.inventoryapi.RawHttp.Response;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * C1, T3, G10 through real Tomcat: TRACE is handled by Spring MVC like any other unsupported method (the same status,
 * text/plain body and Allow methods as PUT) and never echoes the request. MockMvc skips Tomcat's own TRACE handling,
 * so every request is written to a raw socket. Not @Transactional; the tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TraceIntegrationTest {

    private static final String SEEDED = "ABC-1";
    private static final String PROBE_HEADER = "X-Trace-Probe";
    private static final String PROBE_VALUE = "s3cret";
    private static final MediaType MESSAGE_HTTP = MediaType.parseMediaType("message/http");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        // test-only deletes; the application never deletes key, ledger or sku rows (G5, R9)
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(SEEDED).update();
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, 5, 'add')")
                .param(SEEDED).update();
    }

    /** Sends a body-less {@code method path} with Host, Accept JSON, Content-Length 0 and Connection: close. */
    private Response send(String method, String path, String... extraHeaders) throws IOException {
        StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\n")
                .append(RawHttp.header(HOST, "localhost:" + port))
                .append(RawHttp.header(ACCEPT, APPLICATION_JSON_VALUE));
        for (String line : extraHeaders) {
            head.append(line);
        }
        head.append(RawHttp.header(CONTENT_LENGTH, "0"))
                .append(RawHttp.header(CONNECTION, "close"));
        return RawHttp.send(port, head.toString(), "");
    }

    private static Set<String> methods(String commaSeparated) {
        return Set.of(commaSeparated.split(", "));
    }

    private static void assertText(Response response, int status, String body) {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.contentType()).as(response.toString()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).as(response.toString()).isEqualTo(body);
    }

    /** C1, T3: TRACE on a spec route is 405 with Spring's Allow, the same as PUT on that route. */
    @ParameterizedTest(name = "TRACE {0} → 405, Allow: {1}")
    @CsvSource(delimiter = '|', value = {
        "/inventory/ABC-1          | GET, POST",
        "/inventory/ABC-1/purchase | POST",
        "/inventory                | GET"
    })
    void traceMatchesPutOnSpecOperations(String path, String allow) throws IOException {
        Response put = send("PUT", path);
        assertText(put, 405, "Method Not Allowed");
        assertThat(put.allow()).as(put.toString()).isEqualTo(methods(allow));

        Response trace = send("TRACE", path);

        assertText(trace, 405, "Method Not Allowed");
        assertThat(trace.allow()).as(trace.toString()).isEqualTo(put.allow());
    }

    /** C1, OQ1: an unknown path is 404 for TRACE as for PUT. */
    @Test
    void traceOnUnknownInventoryPathMatchesPut() throws IOException {
        assertText(send("PUT", "/inventory/a/b/c"), 404, "Not Found");

        assertText(send("TRACE", "/inventory/a/b/c"), 404, "Not Found");
    }

    /** C1: TRACE never reflects request headers (no message/http echo), on a route or an unknown path. */
    @ParameterizedTest(name = "TRACE {0} never echoes")
    @ValueSource(strings = {"/inventory/ABC-1", "/nope"})
    void traceNeverEchoesRequest(String path) throws IOException {
        Response response = send("TRACE", path, RawHttp.header(PROBE_HEADER, PROBE_VALUE));

        assertThat(response.body()).as(response.toString()).doesNotContain(PROBE_VALUE);
        MediaType contentType = response.contentType();
        assertThat(contentType == null || !contentType.isCompatibleWith(MESSAGE_HTTP)).as(response.toString())
                .isTrue();
    }

    /** S6, C1: actuator keeps library behaviour; TRACE matches POST there (JSON 405, same Allow). */
    @Test
    void traceOnActuatorKeepsLibraryBehaviour() throws IOException {
        Response post = send("POST", "/actuator/health");

        Response trace = send("TRACE", "/actuator/health");

        assertThat(trace.status()).as(trace.toString()).isEqualTo(405);
        assertThat(trace.contentType()).as(trace.toString()).isNotNull();
        assertThat(trace.contentType().isCompatibleWith(APPLICATION_JSON)).as(trace.toString()).isTrue();
        assertThat(post.allow()).as(post.toString()).isNotEmpty();
        assertThat(trace.allow()).as(trace.toString()).isEqualTo(post.allow());
    }
}
