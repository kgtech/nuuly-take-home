package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.RawHttp.header;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.assertj.core.api.Assertions.assertThat;

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

    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("POST /%69nventory/ABC-1;lot=7", "{\"quantity\":5}", null, 400, "Invalid request"),
                Arguments.of("GET /%69nventory/ABC-1;x=y", "", null, 404, "SKU not found"),
                Arguments.of("POST /inventory;v=1/ABC-1;x/purchase", "{\"quantity\":1}", null, 404, "SKU not found"),
                Arguments.of("POST /%69nventory/ABC-1", "{\"quantity\":1}", "application/json;q=0", 400, "Invalid request"),
                Arguments.of("POST /%69nventory/ABC-1", "{\"quantity\":1}", "application/json;q=0, */*;q=0.1", 400,
                        "Invalid request"));
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
    }
}
