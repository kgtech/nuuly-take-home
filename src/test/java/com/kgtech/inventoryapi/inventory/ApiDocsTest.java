package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * OQ2, S6, S12, Z3: springdoc documents exactly the spec's operations, codes (plus GET /inventory's 400) and media
 * types, and its own paths keep library behaviour. Same annotations as InventoryApiIntegrationTest so the context
 * and container are reused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsTest {

    @Autowired
    MockMvc mvc;

    /** The app's port (compose.override.yaml, README); springdoc derives the documented server URL from the request. */
    private static final int APP_PORT = 8080;

    /** Every export request and every request compared with the export is issued as if on the app's port. */
    private static MockHttpServletRequestBuilder docsRequest(String path) {
        return get(path).with(request -> {
            request.setServerPort(APP_PORT);
            return request;
        });
    }

    private String apiDocs() throws Exception {
        return mvc.perform(docsRequest("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Map<String, Map<String, Map<String, Object>>> operations() throws Exception {
        return JsonPath.read(apiDocs(), "$.paths");
    }

    @Test
    void apiDocsAreServedAsJson() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void specPathsAreExactlyTheFour() throws Exception {
        Map<String, Map<String, Map<String, Object>>> paths = operations();

        assertThat(paths.keySet())
                .containsExactlyInAnyOrder("/inventory", "/inventory/{skuId}", "/inventory/{skuId}/purchase");
        assertThat(paths.get("/inventory").keySet()).containsExactly("get");
        assertThat(paths.get("/inventory/{skuId}").keySet()).containsExactlyInAnyOrder("get", "post");
        assertThat(paths.get("/inventory/{skuId}/purchase").keySet()).containsExactly("post");
    }

    @ParameterizedTest(name = "{1} {0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "/inventory/{skuId}          | get  | 200,404",
        "/inventory/{skuId}          | post | 200,400",
        "/inventory/{skuId}/purchase | post | 200,400,404",
        "/inventory                  | get  | 200,400"
    })
    void eachOperationListsExactlyItsSpecCodes(String path, String method, String codes) throws Exception {
        Map<String, Object> responses = JsonPath.read(apiDocs(), "$.paths['" + path + "']." + method + ".responses");

        assertThat(responses.keySet()).containsExactlyInAnyOrder(codes.split(","));
    }

    @Test
    void errorResponsesAreTextPlainAndSuccessIsJson() throws Exception {
        String docs = apiDocs();
        int checked = 0;
        for (var path : operations().entrySet()) {
            for (String method : path.getValue().keySet()) {
                Map<String, Object> responses =
                        JsonPath.read(docs, "$.paths['" + path.getKey() + "']." + method + ".responses");
                for (String code : responses.keySet()) {
                    Map<String, Object> contentTypes = JsonPath.read(docs,
                            "$.paths['" + path.getKey() + "']." + method + ".responses['" + code + "'].content");
                    String where = method + " " + path.getKey() + " " + code;
                    if (code.equals("200")) {
                        assertThat(contentTypes.keySet()).as(where).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                    } else {
                        assertThat(contentTypes.keySet()).as(where).containsExactly(MediaType.TEXT_PLAIN_VALUE);
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(9);
    }

    /** S6, S12: the @Hidden catch-all and override-with-generic-response=false keep 500 off the operations. */
    @Test
    void catchAllIsNotAddedToOperations() throws Exception {
        String docs = apiDocs();
        for (var path : operations().entrySet()) {
            for (String method : path.getValue().keySet()) {
                Map<String, Object> responses =
                        JsonPath.read(docs, "$.paths['" + path.getKey() + "']." + method + ".responses");
                assertThat(responses).as(method + " " + path.getKey()).doesNotContainKeys("500", "default");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> idempotencyKeyParameters(String path, String method) throws Exception {
        Map<String, Object> operation = JsonPath.read(apiDocs(), "$.paths['" + path + "']." + method);
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) operation.getOrDefault("parameters", List.of());
        return parameters.stream().filter(p -> IDEMPOTENCY_KEY.equals(p.get("name"))).toList();
    }

    /** G8, S3: both POSTs document the optional Idempotency-Key header as a UUID string. */
    @ParameterizedTest
    @ValueSource(strings = {"/inventory/{skuId}", "/inventory/{skuId}/purchase"})
    void postsDocumentOptionalIdempotencyKeyHeader(String path) throws Exception {
        List<Map<String, Object>> parameters = idempotencyKeyParameters(path, "post");

        assertThat(parameters).singleElement().satisfies(parameter -> {
            assertThat(parameter.get("in")).isEqualTo("header");
            assertThat(parameter.get("required")).as("required absent or false").isIn(null, false);
            assertThat(parameter.get("schema")).isInstanceOfSatisfying(Map.class, schema -> {
                assertThat(schema.get("type")).isEqualTo("string");
                assertThat(schema.get("format")).isEqualTo("uuid");
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/inventory/{skuId}", "/inventory"})
    void getsHaveNoIdempotencyKeyHeader(String path) throws Exception {
        assertThat(idempotencyKeyParameters(path, "get")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> listQueryParameters() throws Exception {
        Map<String, Object> operation = JsonPath.read(apiDocs(), "$.paths['/inventory'].get");
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) operation.getOrDefault("parameters", List.of());
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> parameter : parameters) {
            byName.put((String) parameter.get("name"), parameter);
        }
        return byName;
    }

    /** G9, R4, R8: GET /inventory documents optional limit (integer 1–250) and after (string) query parameters. */
    @Test
    void listDocumentsOptionalLimitAndAfterQueryParameters() throws Exception {
        Map<String, Map<String, Object>> parameters = listQueryParameters();

        assertThat(parameters.keySet()).containsExactlyInAnyOrder("limit", "after");
        for (Map<String, Object> parameter : parameters.values()) {
            assertThat(parameter.get("in")).as(parameter.toString()).isEqualTo("query");
            assertThat(parameter.get("required")).as("required absent or false").isIn(null, false);
            assertThat(parameter.get("description")).as(parameter.toString()).isInstanceOf(String.class);
        }
        assertThat(parameters.get("limit").get("schema")).isInstanceOfSatisfying(Map.class, schema -> {
            assertThat(schema.get("type")).isEqualTo("integer");
            assertThat(((Number) schema.get("minimum")).intValue()).isEqualTo(1);
            assertThat(((Number) schema.get("maximum")).intValue()).isEqualTo(250);
        });
        assertThat(parameters.get("after").get("schema")).isInstanceOfSatisfying(Map.class,
                schema -> assertThat(schema.get("type")).isEqualTo("string"));
    }

    /** Z3, S12: GET /inventory documents its 400 as text/plain, and after says it must not be repeated. */
    @Test
    void listDocuments400AndUnrepeatableAfter() throws Exception {
        String docs = apiDocs();
        Map<String, Object> responses = JsonPath.read(docs, "$.paths['/inventory'].get.responses");
        assertThat(responses).containsKey("400");
        Map<String, Object> content = JsonPath.read(docs, "$.paths['/inventory'].get.responses['400'].content");

        assertThat(content.keySet()).containsExactly(MediaType.TEXT_PLAIN_VALUE);
        assertThat((String) listQueryParameters().get("after").get("description")).contains("must not be repeated");
    }

    /** G9: the 200 response documents the Link header; the other operations have none. */
    @Test
    void listDocumentsLinkResponseHeader() throws Exception {
        String docs = apiDocs();
        Map<String, Object> ok = JsonPath.read(docs, "$.paths['/inventory'].get.responses['200']");
        assertThat(ok).containsKey("headers");
        Map<String, Map<String, Object>> headers =
                JsonPath.read(docs, "$.paths['/inventory'].get.responses['200'].headers");

        assertThat(headers.keySet()).containsExactly(LINK);
        assertThat(headers.get(LINK).get("description")).isInstanceOf(String.class);
        assertThat(headers.get(LINK).get("schema")).isInstanceOfSatisfying(Map.class,
                schema -> assertThat(schema.get("type")).isEqualTo("string"));
        List<Object> otherHeaders = JsonPath.read(docs, "$.paths['/inventory/{skuId}'].*.responses.*.headers");
        otherHeaders.addAll(JsonPath.read(docs, "$.paths['/inventory/{skuId}/purchase'].*.responses.*.headers"));
        assertThat(otherHeaders).isEmpty();
    }

    // ---- D7, AC1 (#8): the committed openapi.yaml export and its content ----

    private static final Path OPENAPI_YAML = Path.of("openapi.yaml");

    private byte[] servedYamlBytes() throws Exception {
        return mvc.perform(docsRequest("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(byte[] yaml) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new String(yaml, UTF_8));
    }

    private Map<String, Object> exported() throws Exception {
        return parseYaml(servedYamlBytes());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node, String... keys) {
        Object current = node;
        for (String key : keys) {
            assertThat(current).as("parent of " + key).isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(key);
        }
        assertThat(current).as(String.join(".", keys)).isInstanceOf(Map.class);
        return (Map<String, Object>) current;
    }

    /** Follows a local $ref (#/components/schemas/X) in the exported document. */
    private static Map<String, Object> resolve(Map<String, Object> doc, Map<String, Object> schema) {
        Object ref = schema.get("$ref");
        if (ref == null) {
            return schema;
        }
        String[] parts = ((String) ref).substring(2).split("/");
        return map(doc, parts);
    }

    /**
     * D7, AC1: the test writes /v3/api-docs.yaml to openapi.yaml at the project root (Gradle's test working
     * directory) and fails when the committed file was missing or different, so a stale export can't be merged.
     */
    @Test
    void openApiYamlIsRegeneratedAndCommitted() throws Exception {
        assertThat(Path.of("settings.gradle.kts")).as("test working directory is the project root").exists();
        byte[] current = servedYamlBytes();
        byte[] previous = Files.exists(OPENAPI_YAML) ? Files.readAllBytes(OPENAPI_YAML) : null;

        Files.write(OPENAPI_YAML, current);

        assertThat(previous != null && Arrays.equals(previous, current))
                .withFailMessage("openapi.yaml regenerated; commit it")
                .isTrue();
    }

    @Test
    void exportedYamlMatchesServedJson() throws Exception {
        Map<String, Object> json = JsonPath.read(apiDocs(), "$");

        assertThat(exported()).isEqualTo(json);
    }

    /** R1-2 (#8): the export declares one server, the app on port 8080, not MockMvc's default http://localhost. */
    @Test
    @SuppressWarnings("unchecked")
    void exportDeclaresServerOnPort8080() throws Exception {
        Object servers = exported().get("servers");

        assertThat(servers).as("servers").isInstanceOf(List.class);
        assertThat((List<Map<String, Object>>) servers).singleElement()
                .satisfies(server -> assertThat(server.get("url")).isEqualTo("http://localhost:8080"));
    }

    /** S5, S12: 5 error responses, each text/plain only; the 4 successes are application/json only. */
    @Test
    void exportedErrorResponsesAreTextPlain() throws Exception {
        Map<String, Object> paths = map(exported(), "paths");
        int errors = 0;
        int successes = 0;
        for (String path : paths.keySet()) {
            Map<String, Object> methods = map(paths, path);
            for (String method : methods.keySet()) {
                Map<String, Object> responses = map(methods, method, "responses");
                for (String code : responses.keySet()) {
                    Map<String, Object> content = map(responses, code, "content");
                    String where = method + " " + path + " " + code;
                    if (code.equals("200")) {
                        assertThat(content.keySet()).as(where).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                        successes++;
                    } else {
                        assertThat(content.keySet()).as(where).containsExactly(MediaType.TEXT_PLAIN_VALUE);
                        errors++;
                    }
                }
            }
        }
        assertThat(errors).as("error responses").isEqualTo(5);
        assertThat(successes).as("200 responses").isEqualTo(4);
    }

    private static void collectWildcards(Object node, String where, List<String> found) {
        if (node instanceof Map<?, ?> m) {
            for (var entry : m.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.equals(MediaType.ALL_VALUE)) {
                    found.add(where + "/" + key);
                }
                collectWildcards(entry.getValue(), where + "/" + key, found);
            }
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                collectWildcards(l.get(i), where + "[" + i + "]", found);
            }
        } else if (MediaType.ALL_VALUE.equals(node)) {
            found.add(where);
        }
    }

    @Test
    void exportHasNoWildcardMediaType() throws Exception {
        List<String> wildcards = new ArrayList<>();
        collectWildcards(exported(), "", wildcards);

        assertThat(wildcards).isEmpty();
    }

    /** G2, V2: the response quantity is a 64-bit integer on both item responses and the list's items. */
    @Test
    void responseQuantityIsInt64() throws Exception {
        Map<String, Object> doc = exported();
        List<Map<String, Object>> itemSchemas = List.of(
                map(doc, "paths", "/inventory/{skuId}", "get", "responses", "200", "content",
                        MediaType.APPLICATION_JSON_VALUE, "schema"),
                map(doc, "paths", "/inventory/{skuId}", "post", "responses", "200", "content",
                        MediaType.APPLICATION_JSON_VALUE, "schema"),
                map(doc, "paths", "/inventory/{skuId}/purchase", "post", "responses", "200", "content",
                        MediaType.APPLICATION_JSON_VALUE, "schema"),
                map(resolve(doc, map(doc, "paths", "/inventory", "get", "responses", "200", "content",
                        MediaType.APPLICATION_JSON_VALUE, "schema")), "items"));
        for (Map<String, Object> schema : itemSchemas) {
            Map<String, Object> quantity = map(resolve(doc, schema), "properties", "quantity");

            assertThat(quantity.get("type")).as(schema.toString()).isEqualTo("integer");
            assertThat(quantity.get("format")).as(schema.toString()).isEqualTo("int64");
        }
    }

    /** G13, V2: the request quantity is a required 32-bit integer with minimum 1. */
    @ParameterizedTest
    @ValueSource(strings = {"/inventory/{skuId}", "/inventory/{skuId}/purchase"})
    void requestQuantityIsInt32WithMinimum1(String path) throws Exception {
        Map<String, Object> doc = exported();
        Map<String, Object> body = resolve(doc, map(doc, "paths", path, "post", "requestBody", "content",
                MediaType.APPLICATION_JSON_VALUE, "schema"));
        Map<String, Object> quantity = map(body, "properties", "quantity");

        assertThat(body.get("required")).isEqualTo(List.of("quantity"));
        assertThat(quantity.get("type")).isEqualTo("integer");
        assertThat(quantity.get("format")).isEqualTo("int32");
        assertThat(quantity.get("minimum")).isInstanceOfSatisfying(Number.class,
                minimum -> assertThat(minimum.intValue()).isEqualTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/inventory/{skuId}", "/inventory/{skuId}/purchase"})
    void postRequestBodiesAreRequiredJson(String path) throws Exception {
        Map<String, Object> requestBody = map(exported(), "paths", path, "post", "requestBody");

        assertThat(requestBody.get("required")).isEqualTo(true);
        assertThat(map(requestBody, "content").keySet()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
    }

    /** OQ2 (#8): two exports are byte-identical and paths and component keys are sorted. */
    @Test
    void exportIsStable() throws Exception {
        byte[] first = servedYamlBytes();
        byte[] second = servedYamlBytes();
        assertThat(Arrays.equals(first, second)).withFailMessage("two exports differ").isTrue();

        Map<String, Object> doc = parseYaml(first);
        assertThat(List.copyOf(map(doc, "paths").keySet())).as("paths").isSorted();
        Map<String, Object> components = map(doc, "components");
        assertThat(List.copyOf(components.keySet())).as("components").isSorted();
        for (String section : components.keySet()) {
            assertThat(List.copyOf(map(components, section).keySet())).as("components." + section).isSorted();
        }
    }

    /** OQ1-B (#8): operationIds and summaries match the original spec. */
    @ParameterizedTest(name = "{1} {0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "/inventory/{skuId}          | get  | getInventory    | Get inventory for a SKU",
        "/inventory/{skuId}          | post | createInventory | Create or update inventory for a SKU",
        "/inventory/{skuId}/purchase | post | purchaseItem    | Purchase a quantity of a SKU",
        "/inventory                  | get  | listInventory   | List all inventory"
    })
    void operationIdsMatchSpec(String path, String method, String operationId, String summary) throws Exception {
        Map<String, Object> operation = map(exported(), "paths", path, method);

        assertThat(operation.get("operationId")).isEqualTo(operationId);
        assertThat(operation.get("summary")).isEqualTo(summary);
    }

    /** OQ1-B (#8): info matches the original spec. */
    @Test
    void infoMatchesSpec() throws Exception {
        Map<String, Object> info = map(exported(), "info");

        assertThat(info.get("title")).isEqualTo("Inventory API");
        assertThat(info.get("version")).isEqualTo("1.0.0");
    }

    /** R1-4 (#8): every operation is tagged "inventory", and no tag is springdoc's generated "…-controller" name. */
    @Test
    @SuppressWarnings("unchecked")
    void operationsTaggedInventory() throws Exception {
        Map<String, Object> doc = exported();
        Map<String, Object> paths = map(doc, "paths");
        List<String> allTags = new ArrayList<>();
        int checked = 0;
        for (String path : paths.keySet()) {
            Map<String, Object> methods = map(paths, path);
            for (String method : methods.keySet()) {
                Object tags = map(methods, method).get("tags");

                assertThat(tags).as(method + " " + path).isEqualTo(List.of("inventory"));
                allTags.addAll((List<String>) tags);
                checked++;
            }
        }
        for (Map<String, Object> tag : (List<Map<String, Object>>) doc.getOrDefault("tags", List.of())) {
            allTags.add((String) tag.get("name"));
        }
        assertThat(checked).as("operations").isEqualTo(4);
        assertThat(allTags).noneMatch(tag -> tag.contains("controller"));
    }

    @Test
    void swaggerUiRedirectKeepsLibraryBehaviour() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/swagger-ui/index.html")));
    }

    @Test
    void swaggerUiIndexIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
