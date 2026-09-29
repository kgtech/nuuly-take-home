package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.util.Map;
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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.IntegrationTest;

/**
 * Issue #23 (C-04, C-34), F-04 (H11, L21): the request guard's rules hold on every write route of both versions: a raw
 * ';' (or %3B) in the SKU segment, an Accept that excludes JSON, and a body over the cap write nothing. The routes
 * are the spec's and the /v2 add, purchase and details PUT; the guard answers before routing. The filter runs before
 * @Valid (H-M13).
 */
@IntegrationTest
@AutoConfigureMockMvc
class InventoryRequestGuardIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private Map<String, Long> seeded;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "ABC-1", 5);
        seeded = Tables.counts(jdbc);
    }

    private static final String QUANTITY = "{\"quantity\":1";
    private static final String DETAILS = "{\"name\":\"n\"";

    /** A write route of either version: method, path with the SKU ABC-1, and a valid body's JSON without its closing brace. */
    private record Route(HttpMethod method, String path, String body, int cap) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final Route POST_ITEM = new Route(HttpMethod.POST, "/inventory/ABC-1", QUANTITY, 4096);
    private static final Route POST_PURCHASE = new Route(HttpMethod.POST, "/inventory/ABC-1/purchase", QUANTITY, 4096);
    private static final Route V2_POST_ITEM = new Route(HttpMethod.POST, "/v2/inventory/ABC-1", QUANTITY, 4096);
    private static final Route V2_POST_PURCHASE = new Route(HttpMethod.POST, "/v2/inventory/ABC-1/purchase", QUANTITY, 4096);
    private static final Route V2_PUT_DETAILS = new Route(HttpMethod.PUT, "/v2/inventory/ABC-1/details", DETAILS, 65_536);
    private static final Route[] WRITE_ROUTES =
            {POST_ITEM, POST_PURCHASE, V2_POST_ITEM, V2_POST_PURCHASE, V2_PUT_DETAILS};

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder jsonPost(String path) {
        return jsonPost(path, APPLICATION_JSON.toString());
    }

    /** One Accept header exactly: the value under test. */
    private static MockHttpServletRequestBuilder jsonPost(String path, String accept) {
        return write(HttpMethod.POST, path, "{\"quantity\":1}", accept);
    }

    /**
     * A well-formed write, with a valid Idempotency-Key on the /v2 routes only: the unversioned POSTs reject the header
     * (H3). The path is a URI, so %3B reaches the guard undecoded.
     */
    private static MockHttpServletRequestBuilder write(HttpMethod method, String path, String body, String accept) {
        MockHttpServletRequestBuilder request = request(method, URI.create(path)).contentType(APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, accept).content(body);
        return path.startsWith("/v2") ? request.header(IDEMPOTENCY_KEY, UUID.randomUUID().toString()) : request;
    }

    private static MockHttpServletRequestBuilder write(Route route, String path, String accept) {
        return write(route.method(), path, route.body() + "}", accept);
    }

    static Stream<Arguments> matrixSegments() {
        return Stream.of(
                Arguments.of("create", jsonPost("/inventory/ABC-1;lot=7"), 400, "Invalid request"),
                Arguments.of("purchase", jsonPost("/inventory/ABC-1;x/purchase"), 404, "SKU not found"),
                Arguments.of("get", get("/inventory/ABC-1;x=y").accept(APPLICATION_JSON), 404, "SKU not found"),
                Arguments.of("purchase, matrix on the base segment", jsonPost("/inventory;v=1/ABC-1;x/purchase"), 404,
                        "SKU not found"),
                Arguments.of("create, %3B", jsonPost("/inventory/ABC-1%3Blot=7"), 400, "Invalid request"),
                Arguments.of("purchase, %3B", jsonPost("/inventory/ABC-1%3Bx/purchase"), 404, "SKU not found"),
                // the filter runs before @Valid (H-M13): a ';' SKU with a bad body gets the SKU answer
                Arguments.of("purchase, ';' and a bad body",
                        write(HttpMethod.POST, "/inventory/ABC-1;x/purchase", "{\"quantity\":0}", "application/json"), 404,
                        "SKU not found"),
                // F-04: the same rules on every /v2 route
                Arguments.of("v2 get", get("/v2/inventory/ABC-1;x=y").accept(APPLICATION_JSON), 404, "SKU not found"),
                Arguments.of("v2 get, %3B", get(URI.create("/v2/inventory/ABC-1%3Bx")).accept(APPLICATION_JSON), 404,
                        "SKU not found"),
                Arguments.of("v2 add", write(V2_POST_ITEM, "/v2/inventory/ABC-1;lot=7", "application/json"), 400,
                        "Invalid request"),
                Arguments.of("v2 add, %3B", write(V2_POST_ITEM, "/v2/inventory/ABC-1%3Blot=7", "application/json"),
                        400, "Invalid request"),
                Arguments.of("v2 add, ';' and a bad body",
                        write(HttpMethod.POST, "/v2/inventory/ABC-1;x", "{\"quantity\":0}", "application/json"), 400,
                        "Invalid request"),
                Arguments.of("v2 purchase", write(V2_POST_PURCHASE, "/v2/inventory/ABC-1;x/purchase", "application/json"),
                        404, "SKU not found"),
                Arguments.of("v2 purchase, %3B",
                        write(V2_POST_PURCHASE, "/v2/inventory/ABC-1%3Bx/purchase", "application/json"), 404,
                        "SKU not found"),
                Arguments.of("v2 purchase, ';' and a bad body",
                        write(HttpMethod.POST, "/v2/inventory/ABC-1;x/purchase", "{\"quantity\":0}", "application/json"),
                        404, "SKU not found"),
                Arguments.of("v2 purchase, matrix on the literal segments",
                        write(V2_POST_PURCHASE, "/v2;a=b/inventory;v=1/ABC-1;x/purchase;y", "application/json"), 404,
                        "SKU not found"),
                Arguments.of("v2 details", write(V2_PUT_DETAILS, "/v2/inventory/ABC-1;x/details", "application/json"),
                        400, "Invalid request"),
                Arguments.of("v2 details, %3B", write(V2_PUT_DETAILS, "/v2/inventory/ABC-1%3Bx/details",
                        "application/json"), 400, "Invalid request"));
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
                Arguments.of("same, other order", "*/*;q=0.1, application/json;q=0"),
                // equally specific ranges: the first listed decides (M-13 tie-break, replaces C3's "highest q")
                Arguments.of("tie, first listed refuses", "application/json;q=0, application/json;q=1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonExcluded")
    void postWhoseAcceptExcludesJsonIs400AndWritesNothing(String what, String accept) throws Exception {
        for (Route route : WRITE_ROUTES) {
            MockHttpServletResponse response = send(write(route, route.path(), accept));

            assertThat(response.getStatus()).as(route.toString()).isEqualTo(400);
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
        // equally specific ranges: the first listed decides
        assertThat(send(jsonPost("/inventory/ABC-1", "application/json;q=1, application/json;q=0")).getStatus())
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'ABC-1'").query(Long.class).single()).isEqualTo(10);
    }

    /** All Accept lines count: a refusal on a second line still refuses. */
    @Test
    void secondAcceptLineIsHonoured() throws Exception {
        MockHttpServletResponse response = send(jsonPost("/inventory/ABC-1", "*/*").header(HttpHeaders.ACCEPT,
                "application/json;q=0"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertNothingWritten();
    }

    /** A valid JSON body of exactly {@code bytes} bytes: the route's body plus an ignored property (G13). */
    private static String padded(Route route, int bytes) {
        String head = route.body() + ",\"pad\":\"";
        return head + "d".repeat(bytes - head.length() - 2) + "\"}";
    }

    /** A body over the cap is 400 by Content-Length alone (A19, H11): 4 KB on every POST, 64 KB on the details PUT. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("capRoutes")
    void bodyOverTheCapIs400AndWritesNothing(Route route) throws Exception {
        MockHttpServletResponse response = send(
                write(route.method(), route.path(), padded(route, route.cap() + 1), "application/json"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).isEqualTo("Invalid request");
        assertNothingWritten();
    }

    static Stream<Route> capRoutes() {
        return Stream.of(WRITE_ROUTES);
    }

    /** The cap itself passes the filter: the spec's routes at 4096 bytes reach the write. */
    @Test
    void bodyAtTheCapPassesTheGuard() throws Exception {
        assertThat(send(write(POST_PURCHASE.method(), POST_PURCHASE.path(), padded(POST_PURCHASE, 4096),
                "application/json")).getStatus()).isEqualTo(200);
        assertThat(send(write(POST_ITEM.method(), POST_ITEM.path(), padded(POST_ITEM, 4096), "application/json"))
                .getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'ABC-1'").query(Long.class).single()).isEqualTo(5);
        // the /v2 POSTs have the same 4096-byte cap, on a SKU of their own so ABC-1's balance stays untouched
        Route add = new Route(HttpMethod.POST, "/v2/inventory/AT-CAP", QUANTITY, 4096);
        Route purchase = new Route(HttpMethod.POST, "/v2/inventory/AT-CAP/purchase", QUANTITY, 4096);
        MockHttpServletResponse added = send(write(add.method(), add.path(), padded(add, 4096), "application/json"));
        assertThat(added.getStatus()).as(added.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse bought = send(write(purchase.method(), purchase.path(), padded(purchase, 4096),
                "application/json"));
        assertThat(bought.getStatus()).as(bought.getContentAsString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'AT-CAP'").query(Long.class).single()).isZero();
        // the details PUT keeps 65,536
        Route details = new Route(HttpMethod.PUT, "/v2/inventory/AT-CAP/details", DETAILS, 65_536);
        assertThat(send(write(details.method(), details.path(), padded(details, 65_536), "application/json"))
                .getStatus()).isEqualTo(200);
    }

    private void assertNothingWritten() {
        assertThat(jdbc.sql("SELECT quantity, version FROM sku WHERE sku_id = 'ABC-1'").query().singleRow())
                .containsEntry("quantity", 5L).containsEntry("version", 1L);
        assertThat(Tables.counts(jdbc)).isEqualTo(seeded);
    }
}
