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
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.RawHttp.Response;

/**
 * S6, C1, G10 through real Tomcat: errors on /actuator/** and the springdoc paths keep library behaviour (Spring
 * Boot's /error JSON, the empty 406), except an undecodable query, which is 400 text/plain on every path (C1, Z3);
 * /inventory/** and unknown paths keep the text/plain contract. The /error dispatch only happens in a real servlet
 * container, so every request is written to a raw socket. Reads no tables.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
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

    /** C1, Z3: an undecodable query is 400 text/plain "Invalid request" on library paths too, never Boot's JSON. */
    @ParameterizedTest(name = "GET {0} → 400 Invalid request")
    @ValueSource(strings = {"/actuator/health?x=%zz", "/swagger-ui.html?x=%zz"})
    void libraryPathUndecodableQueryReturnsInvalidRequest(String target) throws IOException {
        Response response = send("GET", target, APPLICATION_JSON_VALUE);

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(isTextPlain(response)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo("Invalid request");
    }

    /**
     * C1, Z3: an undecodable query logs exactly one WARN line from InventoryErrorAdvice with the method and path, no
     * stack trace after it, and no Tomcat "Servlet.service() ... threw exception" ERROR.
     */
    @ParameterizedTest(name = "GET {0}{1} → one WARN line")
    @CsvSource({
        "/actuator/health, ?x=%zz",
        "/inventory, ?after=%zz"
    })
    void undecodableQueryLogsOneWarnLine(String path, String query, CapturedOutput output) throws IOException {
        Response response = send("GET", path + query, APPLICATION_JSON_VALUE);
        assertThat(response.status()).as(response.toString()).isEqualTo(400);

        List<String> lines = output.getAll().lines().toList();
        List<String> warnings = lines.stream()
                .filter(line -> line.contains(" WARN ") && line.contains("InventoryErrorAdvice"))
                .toList();
        assertThat(warnings).as(output.getAll()).singleElement()
                .satisfies(line -> assertThat(line).contains("GET").contains(path));
        assertThat(lines).as(output.getAll())
                .noneMatch(line -> line.contains("Servlet.service()") || line.contains("threw exception"));
        List<String> afterWarning = lines.subList(lines.indexOf(warnings.getFirst()) + 1, lines.size());
        assertThat(afterWarning).as(output.getAll()).noneMatch(line -> line.startsWith("\tat "));
    }

    /** H12: the group JSON and group YAML paths (/v3/api-docs.yaml/{group}) are library paths too. */
    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs/inventory", "/v3/api-docs.yaml/inventory"})
    void apiDocsWithXmlAcceptIsNotInventoryText(String path) throws IOException {
        Response response = send("GET", path, APPLICATION_XML_VALUE);

        assertThat(response.status()).as(response.toString()).isEqualTo(406);
        assertThat(isTextPlain(response)).as(response.toString()).isFalse();
        assertThat(response.body()).isNotEqualTo("Not Acceptable");
    }

    @ParameterizedTest(name = "{0} {1} → {2} Boot JSON")
    @CsvSource({
        "POST, /actuator/health, 405",
        "GET, /v3/api-docs/nope, 404",
        "GET, /v3/api-docs.yaml/nope, 404",
        "POST, /v3/api-docs/inventory, 405",
        "POST, /v3/api-docs.yaml/inventory, 405",
        "GET, /swagger-ui/nope.js, 404"
    })
    void otherLibraryErrorsUseBootErrorJson(String method, String path, int status) throws IOException {
        assertBootJson(send(method, path, acceptFor(path)), status, path);
    }

    /** The YAML endpoint only produces application/vnd.oai.openapi, so JSON there is a 406 before the lookup: use any. */
    private static String acceptFor(String path) {
        return path.startsWith("/v3/api-docs.yaml/") ? MediaType.ALL_VALUE : APPLICATION_JSON_VALUE;
    }

    /**
     * H12: springdoc's unknown-group exception is rethrown like any library error, but Boot maps it to 500; the
     * truthful 404 must come back, still with Boot's JSON body (not text/plain), for both URL forms.
     */
    @ParameterizedTest(name = "GET {0} → 404 Boot JSON, no ERROR log")
    @ValueSource(strings = {"/v3/api-docs/nope", "/v3/api-docs.yaml/nope"})
    void unknownDocsGroupIs404WithoutErrorLog(String path, CapturedOutput output) throws IOException {
        assertBootJson(send("GET", path, acceptFor(path)), 404, path);

        assertThat(output.getAll().lines()).noneMatch(line -> line.contains(" ERROR "));
    }

    /** H12: swagger-config (Swagger UI's dropdown) lists both groups, and both URLs it gives are served. */
    @Test
    void swaggerConfigListsBothGroups() throws IOException {
        Response config = send("GET", "/v3/api-docs/swagger-config", APPLICATION_JSON_VALUE);

        assertThat(config.status()).as(config.toString()).isEqualTo(200);
        List<String> names = JsonPath.read(config.body(), "$.urls[*].name");
        List<String> urls = JsonPath.read(config.body(), "$.urls[*].url");
        assertThat(names).containsExactlyInAnyOrder("inventory", "inventory-v2");
        assertThat(urls).containsExactlyInAnyOrder("/v3/api-docs/inventory", "/v3/api-docs/inventory-v2");
        for (String url : urls) {
            Response docs = send("GET", url, APPLICATION_JSON_VALUE);
            assertThat(docs.status()).as(docs.toString()).isEqualTo(200);
        }
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
