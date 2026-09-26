package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.APPLICATION_XML_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.RawHttp.Response;

/**
 * S6, C1, G10 through real Tomcat: errors on /actuator/** and the springdoc paths keep library behaviour (Spring
 * Boot's /error JSON, the empty 406), while /inventory/** and unknown paths keep the text/plain contract. The /error
 * dispatch only happens in a real servlet container, so every request is written to a raw socket. Reads no tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LibraryPathErrorsIntegrationTest {

    @LocalServerPort
    int port;

    /** Sends a body-less {@code method target} with Host, the given Accept, Content-Length 0 and Connection: close. */
    private Response send(String method, String target, String accept) throws IOException {
        String head = method + " " + target + " HTTP/1.1\r\n"
                + RawHttp.header(HOST, "localhost:" + port)
                + RawHttp.header(ACCEPT, accept)
                + RawHttp.header(CONTENT_LENGTH, "0")
                + RawHttp.header(CONNECTION, "close");
        return RawHttp.send(port, head, "");
    }

    private static boolean isTextPlain(Response response) {
        MediaType contentType = response.contentType();
        return contentType != null && contentType.isCompatibleWith(TEXT_PLAIN);
    }

    /** Spring Boot's /error JSON for {@code status} on {@code path}. */
    private static void assertBootJson(Response response, int status, String path) {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.contentType()).as(response.toString()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(APPLICATION_JSON)).as(response.toString()).isTrue();
        Integer bodyStatus = JsonPath.read(response.body(), "$.status");
        String bodyPath = JsonPath.read(response.body(), "$.path");
        assertThat(bodyStatus).isEqualTo(status);
        assertThat(bodyPath).isEqualTo(path);
    }

    /** S6: the 406 is Boot's own (no text/plain "Not Acceptable" from the inventory advice). */
    @Test
    void actuatorHealthWithXmlAcceptIsNotInventoryText() throws IOException {
        Response response = send("GET", "/actuator/health", APPLICATION_XML_VALUE);

        assertThat(response.status()).as(response.toString()).isEqualTo(406);
        assertThat(isTextPlain(response)).as(response.toString()).isFalse();
        assertThat(response.body()).isNotEqualTo("Not Acceptable");
    }

    @Test
    void unknownActuatorPathUsesBootErrorJson() throws IOException {
        assertBootJson(send("GET", "/actuator/nope", APPLICATION_JSON_VALUE), 404, "/actuator/nope");
    }

    /** OQ5: an undecodable query on a library path is Boot's JSON 400, not the inventory "Invalid request". */
    @Test
    void actuatorUndecodableQueryUsesBootErrorJson() throws IOException {
        Response response = send("GET", "/actuator/health?x=%zz", APPLICATION_JSON_VALUE);

        assertBootJson(response, 400, "/actuator/health");
        assertThat(response.body()).isNotEqualTo("Invalid request");
    }

    @Test
    void apiDocsWithXmlAcceptIsNotInventoryText() throws IOException {
        Response response = send("GET", "/v3/api-docs", APPLICATION_XML_VALUE);

        assertThat(response.status()).as(response.toString()).isEqualTo(406);
        assertThat(isTextPlain(response)).as(response.toString()).isFalse();
        assertThat(response.body()).isNotEqualTo("Not Acceptable");
    }

    @ParameterizedTest(name = "{0} {1} → {2} Boot JSON")
    @CsvSource({
        "POST, /actuator/health, 405",
        "GET, /v3/api-docs/nope, 404",
        "GET, /swagger-ui/nope.js, 404"
    })
    void otherLibraryErrorsUseBootErrorJson(String method, String path, int status) throws IOException {
        assertBootJson(send(method, path, APPLICATION_JSON_VALUE), status, path);
    }

    /** AC5 controls: the inventory contract is unchanged on /inventory/** and unknown paths (look-alikes too). */
    @ParameterizedTest(name = "{0} {1} → {2} {3}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        "GET    | /nope          | 404 | Not Found          | -",
        "GET    | /nope?x=%zz    | 404 | Not Found          | -",
        "GET    | /inventory/a/b | 404 | Not Found          | -",
        "GET    | /actuatorx     | 404 | Not Found          | -",
        "DELETE | /inventory/x   | 405 | Method Not Allowed | GET, POST",
        "PUT    | /inventory     | 405 | Method Not Allowed | GET"
    })
    void inventoryAndUnknownPathsKeepTextPlain(String method, String path, int status, String body, String allow)
            throws IOException {
        Response response = send(method, path, APPLICATION_JSON_VALUE);

        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(isTextPlain(response)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo(body);
        assertThat(response.allow()).as(response.toString())
                .isEqualTo(allow == null ? null : Set.of(allow.split(", ")));
    }

    /** S6: library successes are untouched: health UP, the Swagger UI redirect. */
    @Test
    void healthStillUpAndSwaggerRedirectUnchanged() throws IOException {
        Response health = send("GET", "/actuator/health", APPLICATION_JSON_VALUE);
        assertThat(health.status()).as(health.toString()).isEqualTo(200);
        String status = JsonPath.read(health.body(), "$.status");
        assertThat(status).isEqualTo("UP");

        Response swagger = send("GET", "/swagger-ui.html", MediaType.ALL_VALUE);
        assertThat(swagger.status()).as(swagger.toString()).isEqualTo(302);
        assertThat(swagger.headers().getFirst(LOCATION)).as(swagger.toString()).isNotBlank();
    }
}
