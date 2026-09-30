package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.RawHttp;
import com.kgtech.inventoryapi.RawHttp.Response;
import com.kgtech.inventoryapi.Tables;

/**
 * C3, G11, S2 through real Tomcat (OQ-13): ";" content in the skuId segment is part of the ID, ";" on the literal
 * segments is ignored as Spring does, and neither bypasses the POST Accept q=0 check. MockMvc does not route the raw
 * path as Tomcat does, so every request is written to a raw socket; the MockMvc rows are
 * InventoryRequestValidationTest#skuIdSegmentKeepsSemicolonContent. Not @Transactional; the tables are reset before
 * each test.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuSegmentTomcatIntegrationTest {

    private static final String SEEDED = "ABC-1";
    private static final String QUANTITY_BODY = "{\"quantity\":1}";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, SEEDED, 5);
    }

    /** Sends {@code method target} with Host, Accept JSON and Connection: close, plus a JSON body when given. */
    private Response send(String method, String target, String body) throws IOException {
        StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\n")
                .append(RawHttp.header(HOST, "localhost:" + port))
                .append(RawHttp.header(ACCEPT, APPLICATION_JSON_VALUE));
        if (body != null) {
            head.append(RawHttp.header(CONTENT_TYPE, APPLICATION_JSON_VALUE))
                    .append(RawHttp.header(CONTENT_LENGTH,
                            String.valueOf(body.getBytes(StandardCharsets.UTF_8).length)));
        }
        head.append(RawHttp.header(CONNECTION, "close"));
        return RawHttp.send(port, head.toString(), body == null ? "" : body);
    }

    private static void assertText(Response response, int status, String body) {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.contentType()).as(response.toString()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).as(response.toString()).doesNotContain("<html").isEqualTo(body);
    }

    private static void assertJson(Response response, String body) {
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.contentType()).as(response.toString()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(APPLICATION_JSON)).as(response.toString()).isTrue();
        assertThat(response.body()).as(response.toString()).isEqualTo(body);
    }

    private static String seeded(long quantity) {
        return "{\"skuId\":\"" + SEEDED + "\",\"quantity\":" + quantity + "}";
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /**
     * AC1, G11, C3: ";" content in the skuId segment is part of the ID (Tomcat keeps it in the request URI, Spring
     * strips it from @PathVariable), so it never reaches the seeded SKU: create 400, GET and purchase 404, nothing
     * written. The %3B forms already behaved this way.
     */
    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(delimiter = '|', value = {
        "POST | /inventory/ABC-1;lot=7          | 400 | Invalid request",
        "POST | /inventory/ABC-1;               | 400 | Invalid request",
        "POST | /inventory/ABC-1;x/purchase     | 404 | SKU not found",
        "GET  | /inventory/ABC-1;x=y            | 404 | SKU not found",
        "GET  | /inventory/ABC-1;               | 404 | SKU not found",
        "POST | /inventory/ABC-1%3Blot=7        | 400 | Invalid request",
        "POST | /inventory/ABC-1%3Bx/purchase   | 404 | SKU not found",
        "GET  | /inventory/ABC-1%3Bx=y          | 404 | SKU not found"
    })
    void semicolonInSkuIdSegmentIsPartOfTheId(String method, String path, int status, String body)
            throws IOException {
        assertText(send(method, path, "POST".equals(method) ? QUANTITY_BODY : null), status, body);

        assertThat(count("sku")).isEqualTo(1);
        assertThat(count("inventory_ledger")).isEqualTo(1);
        assertJson(send("GET", "/inventory/" + SEEDED, null), seeded(5));
    }

    /** C3 (OQ1): ";" content on the literal segments is ignored, as Spring does; it never changes the SKU. */
    @Test
    void semicolonOnLiteralSegmentsIsIgnored() throws IOException {
        assertJson(send("GET", "/inventory;v=1/" + SEEDED, null), seeded(5));
        assertJson(send("GET", "/inventory;x", null), "[" + seeded(5) + "]");
        assertJson(send("POST", "/inventory/" + SEEDED + "/purchase;x", QUANTITY_BODY), seeded(4));
        assertJson(send("POST", "/inventory;x/" + SEEDED, QUANTITY_BODY), seeded(5));

        Response notAllowed = send("POST", "/inventory;v=1", QUANTITY_BODY);
        assertText(notAllowed, 405, "Method Not Allowed");
        assertThat(notAllowed.allow()).as(notAllowed.toString()).contains("GET");
        assertThat(count("inventory_ledger")).isEqualTo(3);
    }

    /** C3, U2, Y1: ";" on the literal segment does not bypass the POST Accept q=0 check; nothing is written. */
    @Test
    void semicolonOnLiteralSegmentWithAcceptQZeroIsRefused() throws IOException {
        String head = "POST /inventory;v=1/Q0 HTTP/1.1\r\n"
                + RawHttp.header(HOST, "localhost:" + port)
                + RawHttp.header(ACCEPT, "application/json;q=0")
                + RawHttp.header(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                + RawHttp.header(CONTENT_LENGTH, String.valueOf(QUANTITY_BODY.length()))
                + RawHttp.header(CONNECTION, "close");

        assertText(RawHttp.send(port, head, QUANTITY_BODY), 400, "Invalid request");
        assertThat(count("sku")).isEqualTo(1);
        assertThat(count("inventory_ledger")).isEqualTo(1);
    }

    /**
     * C3: an escaped ";" on a literal segment matches no handler, and neither does an empty segment before the skuId
     * (the plan's unverified claim that "//" routes nowhere), so the segment after /inventory is always the skuId once
     * a handler matched.
     */
    @ParameterizedTest(name = "{0} {1} → 404")
    @CsvSource(delimiter = '|', value = {
        "POST | /inventory/ABC-1/purchase%3Bx",
        "GET  | /inventory%3Bx/ABC-1",
        "GET  | /inventory//ABC-1",
        "POST | /inventory//ABC-1",
        "POST | /inventory//ABC-1/purchase"
    })
    void unroutedPathIsNotFound(String method, String path) throws IOException {
        assertText(send(method, path, "POST".equals(method) ? QUANTITY_BODY : null), 404, "Not Found");
        assertThat(count("inventory_ledger")).isEqualTo(1);
    }
}
