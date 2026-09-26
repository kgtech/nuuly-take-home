package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.RawHttp;
import com.kgtech.inventoryapi.RawHttp.Response;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Z3, R4, G10, S5 through real Tomcat: a GET /inventory query string that can't be decoded (a malformed
 * percent-escape or invalid UTF-8) answers 400 text/plain "Invalid request", never 500. MockMvc doesn't decode the
 * query and java.net.URI rejects these escapes, so the request is written to a raw socket. Not @Transactional: the
 * server commits its own transactions, so the tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class InventoryListMalformedQueryIntegrationTest {

    private static final List<String> SEEDED = List.of("A-1", "B-2", "C-3");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        // test-only deletes; the application never deletes ledger or sku rows (G5)
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
        for (String skuId : SEEDED) {
            jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(skuId).update();
            jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, 5, 'add')")
                    .param(skuId).update();
        }
    }

    /** Sends {@code GET /inventory?<query>} byte for byte, so the server sees the escapes exactly as written. */
    private Response get(String query) throws IOException {
        String head = "GET /inventory?" + query + " HTTP/1.1\r\n"
                + RawHttp.header(HOST, "localhost:" + port)
                + RawHttp.header(ACCEPT, APPLICATION_JSON_VALUE)
                + RawHttp.header(CONNECTION, "close");
        return RawHttp.send(port, head, "");
    }

    /** Control: the raw helper reads a normal paged response (chunked or not) through the same path. */
    @Test
    void validQueryReturnsJsonPage() throws IOException {
        Response response = get("limit=2");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.contentType().isCompatibleWith(APPLICATION_JSON)).as(response.toString()).isTrue();
        List<String> skuIds = JsonPath.read(response.body(), "$[*].skuId");
        assertThat(skuIds).containsExactly("A-1", "B-2");
        assertThat(response.headers().getFirst(LINK))
                .isEqualTo("<http://localhost:" + port + "/inventory?limit=2&after=B-2>; rel=\"next\"");
    }

    /** Z3: a malformed escape or invalid UTF-8 anywhere in the query is a client error, not a 500. */
    @ParameterizedTest(name = "?{0} → 400")
    @ValueSource(strings = {
        "after=%zz",
        "after=%",
        "after=a%",
        "after=%FF",
        "after=%C3%28",
        "limit=%zz",
        "limit=2&after=%E2%82",
        "foo=%zz"
    })
    void undecodableQueryReturns400(String query) throws IOException {
        Response response = get(query);

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo("Invalid request");
    }

    /** Z3: a repeated after is rejected over real HTTP too. */
    @Test
    void repeatedAfterReturns400() throws IOException {
        Response response = get("after=A-1&after=B-2");

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo("Invalid request");
    }
}
