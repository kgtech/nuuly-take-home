package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.inventory.OpenApiDocs.V1;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.V2;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.exported;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.json;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.map;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.resolve;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.IntegrationTest;

/**
 * OQ2, S6, S12, Z3, C2, H12: each springdoc group documents exactly its operations, codes (plus GET /inventory's 400)
 * and media types. Group "inventory" is the unversioned API, checked against the spec by SpecConformanceTest (which
 * covers summaries, descriptions, examples, operationIds, request bodies, required flags and minimums there), group
 * "inventory-v2" the /v2 API with C2's skuId schema, the Idempotency-Key, the ETag and the 201 and 412. No test
 * reads the default all-routes document. Same annotations as InventoryApiIntegrationTest so the context and
 * container are reused.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ApiDocsTest {

    @Autowired
    MockMvc mvc;

    private String docs(String group) throws Exception {
        return json(mvc, group);
    }

    private Map<String, Map<String, Map<String, Object>>> operations(String group) throws Exception {
        return JsonPath.read(docs(group), "$.paths");
    }

    private Map<String, Object> exportOf(String group) throws Exception {
        return exported(mvc, group);
    }

    @ParameterizedTest
    @ValueSource(strings = {V1, V2})
    void groupDocsAreServedAsJson(String group) throws Exception {
        mvc.perform(get("/v3/api-docs/" + group))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** H12: the two groups split by path; no /v2 path leaks into openapi.yaml and no unversioned path into v2's. */
    @Test
    void groupsHoldExactlyTheirPaths() throws Exception {
        Map<String, Map<String, Map<String, Object>>> v1 = operations(V1);
        assertThat(v1.keySet()).containsExactlyInAnyOrder("/inventory", "/inventory/{skuId}",
                "/inventory/{skuId}/purchase");
        assertThat(v1.get("/inventory").keySet()).containsExactly("get");
        assertThat(v1.get("/inventory/{skuId}").keySet()).containsExactlyInAnyOrder("get", "post");
        assertThat(v1.get("/inventory/{skuId}/purchase").keySet()).containsExactly("post");

        Map<String, Map<String, Map<String, Object>>> v2 = operations(V2);
        assertThat(v2.keySet()).containsExactlyInAnyOrder("/v2/inventory", "/v2/inventory/{skuId}",
                "/v2/inventory/{skuId}/purchase", "/v2/inventory/{skuId}/details");
        assertThat(v2.get("/v2/inventory").keySet()).containsExactly("get");
        assertThat(v2.get("/v2/inventory/{skuId}").keySet()).as("OD-6: no PUT, no create")
                .containsExactlyInAnyOrder("get", "post");
        assertThat(v2.get("/v2/inventory/{skuId}/purchase").keySet()).containsExactly("post");
        assertThat(v2.get("/v2/inventory/{skuId}/details").keySet()).containsExactly("put");
    }

    /** H12: title and version per group; the unversioned title is the spec's. */
    @Test
    void infoIsPerGroup() throws Exception {
        Map<String, Object> v1 = map(exportOf(V1), "info");
        assertThat(v1.get("title")).isEqualTo("Inventory API");
        assertThat(v1.get("version")).isEqualTo("1.0.0");

        Map<String, Object> v2 = map(exportOf(V2), "info");
        assertThat(v2.get("title")).isInstanceOfSatisfying(String.class,
                title -> assertThat(title).contains("Inventory API", "v2"));
        assertThat(v2.get("version")).isEqualTo("2.0.0");
    }

    @ParameterizedTest(name = "{0} {2} {1} → {3}")
    @CsvSource(delimiter = '|', value = {
        V1 + " | /inventory/{skuId}          | get  | 200,404",
        V1 + " | /inventory/{skuId}          | post | 200,400",
        V1 + " | /inventory/{skuId}/purchase | post | 200,400,404",
        V1 + " | /inventory                  | get  | 200,400",
        V2 + " | /v2/inventory               | get  | 200,400",
        V2 + " | /v2/inventory/{skuId}       | get  | 200,404",
        V2 + " | /v2/inventory/{skuId}       | post | 200,400",
        V2 + " | /v2/inventory/{skuId}/purchase | post | 200,400,404",
        V2 + " | /v2/inventory/{skuId}/details | put | 200,201,400,412"
    })
    void eachOperationListsExactlyItsCodes(String group, String path, String method, String codes) throws Exception {
        Map<String, Object> responses = JsonPath.read(docs(group), "$.paths['" + path + "']." + method + ".responses");

        assertThat(responses.keySet()).containsExactlyInAnyOrder(codes.split(","));
    }

    /** S5, S12: every error response is text/plain only and every success application/json only, in each group. */
    @ParameterizedTest(name = "{0}: {1} errors, {2} successes")
    @CsvSource({V1 + ", 5, 4", V2 + ", 7, 6"})
    void errorResponsesAreTextPlainAndSuccessIsJson(String group, int expectedErrors, int expectedSuccesses)
            throws Exception {
        Map<String, Object> paths = map(exportOf(group), "paths");
        int errors = 0;
        int successes = 0;
        for (String path : paths.keySet()) {
            Map<String, Object> methods = map(paths, path);
            for (String method : methods.keySet()) {
                Map<String, Object> responses = map(methods, method, "responses");
                for (String code : responses.keySet()) {
                    Map<String, Object> content = map(responses, code, "content");
                    String where = group + " " + method + " " + path + " " + code;
                    if (code.equals("200") || code.equals("201")) {
                        assertThat(content.keySet()).as(where).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                        successes++;
                    } else {
                        assertThat(content.keySet()).as(where).containsExactly(MediaType.TEXT_PLAIN_VALUE);
                        errors++;
                    }
                }
            }
        }
        assertThat(errors).as(group + " error responses").isEqualTo(expectedErrors);
        assertThat(successes).as(group + " 2xx responses").isEqualTo(expectedSuccesses);
    }

    /** C-28 (#61): the catch-all handler must stay @Hidden, or springdoc could document a 500 on every operation. */
    @Test
    void catchAllHandlerIsHidden() throws Exception {
        Class<?> advice = Class.forName("com.kgtech.inventoryapi.inventory.web.InventoryErrorAdvice");
        java.lang.reflect.Method anyOther = advice.getDeclaredMethod("anyOther", Exception.class,
                jakarta.servlet.http.HttpServletRequest.class);
        assertThat(anyOther.isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isTrue();
    }

    /** S6, S12: the @Hidden catch-all and override-with-generic-response=false keep 500 off the operations. */
    @ParameterizedTest
    @ValueSource(strings = {V1, V2})
    void catchAllIsNotAddedToOperations(String group) throws Exception {
        String docs = docs(group);
        for (var path : operations(group).entrySet()) {
            for (String method : path.getValue().keySet()) {
                Map<String, Object> responses =
                        JsonPath.read(docs, "$.paths['" + path.getKey() + "']." + method + ".responses");
                assertThat(responses).as(group + " " + method + " " + path.getKey()).doesNotContainKeys("500", "default");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> idempotencyKeyParameters(String group, String path, String method)
            throws Exception {
        Map<String, Object> operation = JsonPath.read(docs(group), "$.paths['" + path + "']." + method);
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) operation.getOrDefault("parameters", List.of());
        return parameters.stream().filter(p -> IDEMPOTENCY_KEY.equals(p.get("name"))).toList();
    }

    /** OD-4, H3: the unversioned POSTs document no Idempotency-Key header (the spec has none; it is rejected). */
    @ParameterizedTest
    @ValueSource(strings = {"/inventory/{skuId}", "/inventory/{skuId}/purchase"})
    void unversionedPostsHaveNoIdempotencyKeyHeader(String path) throws Exception {
        assertThat(idempotencyKeyParameters(V1, path, "post")).isEmpty();
    }

    /** H2, S3: the /v2 POSTs document the Idempotency-Key header as a required UUID string. */
    @ParameterizedTest
    @ValueSource(strings = {"/v2/inventory/{skuId}", "/v2/inventory/{skuId}/purchase"})
    void v2PostsDocumentRequiredIdempotencyKeyHeader(String path) throws Exception {
        List<Map<String, Object>> parameters = idempotencyKeyParameters(V2, path, "post");

        assertThat(parameters).singleElement().satisfies(parameter -> {
            assertThat(parameter.get("in")).isEqualTo("header");
            assertThat(parameter.get("required")).isEqualTo(true);
            assertThat(parameter.get("schema")).isInstanceOfSatisfying(Map.class, schema -> {
                assertThat(schema.get("type")).isEqualTo("string");
                assertThat(schema.get("format")).isEqualTo("uuid");
            });
        });
    }

    @ParameterizedTest
    @CsvSource({V1 + ", /inventory/{skuId}, get", V1 + ", /inventory, get", V2 + ", /v2/inventory/{skuId}, get",
        V2 + ", /v2/inventory, get", V2 + ", /v2/inventory/{skuId}/details, put"})
    void readsAndPutHaveNoIdempotencyKeyHeader(String group, String path, String method) throws Exception {
        assertThat(idempotencyKeyParameters(group, path, method)).as("PUT is idempotent by itself").isEmpty();
    }

    /** OD-6, OD-11: GET carries the ETag; PUT .../details documents it on 200 and 201 and the conditional headers. */
    @Test
    @SuppressWarnings("unchecked")
    void v2ItemResponsesDocumentEtagAndPutDocumentsTheConditionalHeaders() throws Exception {
        String docs = docs(V2);
        for (String[] op : new String[][] {{"/v2/inventory/{skuId}", "get", "200"},
                {"/v2/inventory/{skuId}/details", "put", "200"}, {"/v2/inventory/{skuId}/details", "put", "201"}}) {
            Map<String, Map<String, Object>> headers = JsonPath.read(docs,
                    "$.paths['" + op[0] + "']." + op[1] + ".responses['" + op[2] + "'].headers");
            assertThat(headers.keySet()).as(op[1] + " " + op[0] + " " + op[2]).containsExactly(HttpHeaders.ETAG);
        }
        Map<String, Object> put = JsonPath.read(docs, "$.paths['/v2/inventory/{skuId}/details'].put");
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) put.get("parameters");
        for (String header : new String[] {HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH}) {
            assertThat(parameters.stream().filter(p -> header.equals(p.get("name")))).as(header).singleElement()
                    .satisfies(p -> {
                        assertThat(p.get("in")).isEqualTo("header");
                        assertThat(p.get("required")).isIn(null, false);
                    });
        }
        Map<String, Object> list = JsonPath.read(docs, "$.paths['/v2/inventory'].get.responses['200'].headers");
        assertThat(list.keySet()).containsExactly(LINK);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> listQueryParameters(String group, String path) throws Exception {
        Map<String, Object> operation = JsonPath.read(docs(group), "$.paths['" + path + "'].get");
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) operation.getOrDefault("parameters", List.of());
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> parameter : parameters) {
            byName.put((String) parameter.get("name"), parameter);
        }
        return byName;
    }

    /** OD-5, H4: GET /inventory documents only the optional after query parameter; there is no limit. */
    @Test
    void listDocumentsOnlyTheAfterQueryParameter() throws Exception {
        Map<String, Map<String, Object>> parameters = listQueryParameters(V1, "/inventory");

        assertThat(parameters.keySet()).containsExactly("after");
        assertThat(parameters.get("after").get("in")).isEqualTo("query");
        assertThat(parameters.get("after").get("required")).as("required absent or false").isIn(null, false);
        assertThat(parameters.get("after").get("description")).isInstanceOf(String.class);
        assertThat(parameters.get("after").get("schema")).isInstanceOfSatisfying(Map.class,
                schema -> assertThat(schema.get("type")).isEqualTo("string"));
    }

    /**
     * G9, R4, R8, C2, H5: GET /v2/inventory documents optional limit (integer 1–250, default 250) and after (string)
     * query parameters.
     */
    @Test
    void v2ListDocumentsOptionalLimitAndAfterQueryParameters() throws Exception {
        Map<String, Map<String, Object>> parameters = listQueryParameters(V2, "/v2/inventory");

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
            assertThat(schema.get("default")).as("limit default").isInstanceOfSatisfying(Number.class,
                    value -> assertThat(value.intValue()).isEqualTo(250));
        });
        assertThat((String) parameters.get("limit").get("description")).contains("default 250");
        assertThat(parameters.get("after").get("schema")).isInstanceOfSatisfying(Map.class,
                schema -> assertThat(schema.get("type")).isEqualTo("string"));
    }

    /**
     * C2, OD-5: GET /inventory's description states the fixed page of 250 and the Link, and never mentions a limit;
     * its 200 response describes one page of at most 250 items (Q22-07). The spec's summary stays.
     */
    @Test
    void listDocumentsDefaultPage() throws Exception {
        Map<String, Object> operation = JsonPath.read(docs(V1), "$.paths['/inventory'].get");

        assertThat(operation.get("description")).isInstanceOfSatisfying(String.class, description -> assertThat(
                description).contains("at most 250", "Link").doesNotContain("limit"));
        assertThat(operation.get("summary")).isEqualTo("List all inventory");
        assertThat(JsonPath.<Object>read(docs(V1), "$.paths['/inventory'].get.responses['200'].description"))
                .as("200 response description").isInstanceOfSatisfying(String.class,
                        description -> assertThat(description).contains("250"));
    }

    /** Z3, S12: GET /inventory documents its 400 as text/plain, and after says it must not be repeated. */
    @Test
    void listDocuments400AndUnrepeatableAfter() throws Exception {
        String docs = docs(V1);
        Map<String, Object> responses = JsonPath.read(docs, "$.paths['/inventory'].get.responses");
        assertThat(responses).containsKey("400");
        Map<String, Object> content = JsonPath.read(docs, "$.paths['/inventory'].get.responses['400'].content");

        assertThat(content.keySet()).containsExactly(MediaType.TEXT_PLAIN_VALUE);
        assertThat((String) listQueryParameters(V1, "/inventory").get("after").get("description"))
                .contains("must not be repeated");
    }

    /** G9: the 200 response documents the Link header; the unversioned item operations have no header at all. */
    @Test
    void listDocumentsLinkResponseHeader() throws Exception {
        String docs = docs(V1);
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

    // ---- the exported documents' content ----

    @ParameterizedTest
    @ValueSource(strings = {V1, V2})
    void exportedYamlMatchesServedJson(String group) throws Exception {
        Map<String, Object> json = JsonPath.read(docs(group), "$");

        assertThat(exportOf(group)).isEqualTo(json);
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

    @ParameterizedTest
    @ValueSource(strings = {V1, V2})
    void exportHasNoWildcardMediaType(String group) throws Exception {
        List<String> wildcards = new ArrayList<>();
        collectWildcards(exportOf(group), "", wildcards);

        assertThat(wildcards).isEmpty();
    }

    /**
     * G2, V2: the /v2 response quantity is a 64-bit integer on the SkuItem component, whose minimum is 0 as in the
     * spec's InventoryItem (C2). The unversioned export has the spec's plain "type: integer" (H12, no format;
     * SpecConformanceTest).
     */
    @Test
    void v2ResponseQuantityIsInt64WithMinimum0() throws Exception {
        Map<String, Object> doc = exportOf(V2);
        Map<String, Object> quantity = map(doc, "components", "schemas", "SkuItem", "properties", "quantity");
        Map<String, Object> listItem = map(resolve(doc, map(resolve(doc, map(doc, "paths", "/v2/inventory", "get",
                "responses", "200", "content", MediaType.APPLICATION_JSON_VALUE, "schema")), "items")),
                "properties", "quantity");

        for (Map<String, Object> schema : List.of(quantity, listItem)) {
            assertThat(schema.get("type")).as(schema.toString()).isEqualTo("integer");
            assertThat(schema.get("format")).as(schema.toString()).isEqualTo("int64");
            assertThat(schema.get("minimum")).as(schema.toString()).isInstanceOfSatisfying(Number.class,
                    minimum -> assertThat(minimum.intValue()).isEqualTo(0));
        }
    }

    /** C2, G11: on /v2 each skuId path parameter documents the G11 pattern, minLength 1 and maxLength 64 (S2). */
    @ParameterizedTest(name = "{1} {0}")
    @CsvSource(delimiter = '|', value = {
        "/v2/inventory/{skuId}       | get",
        "/v2/inventory/{skuId}       | post",
        "/v2/inventory/{skuId}/purchase | post",
        "/v2/inventory/{skuId}/details | put"
    })
    @SuppressWarnings("unchecked")
    void v2SkuIdPathParametersDocumentPattern(String path, String method) throws Exception {
        Object parameters = map(exportOf(V2), "paths", path, method).get("parameters");
        assertThat(parameters).as("parameters").isInstanceOf(List.class);

        assertThat(((List<Map<String, Object>>) parameters).stream().filter(p -> "skuId".equals(p.get("name"))))
                .singleElement().satisfies(parameter -> {
                    assertThat(parameter.get("in")).isEqualTo("path");
                    assertThat(parameter.get("required")).isEqualTo(true);
                    Map<String, Object> schema = map(parameter, "schema");
                    assertThat(schema.get("type")).isEqualTo("string");
                    assertThat(schema.get("pattern")).isEqualTo("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
                    assertThat(schema.get("minLength")).isEqualTo(1);
                    assertThat(schema.get("maxLength")).isEqualTo(64);
                });
    }

    /** C2, H12: the unversioned skuId parameters are the spec's plain string: no pattern, minLength or maxLength. */
    @ParameterizedTest(name = "{1} {0}")
    @CsvSource(delimiter = '|', value = {
        "/inventory/{skuId}          | get",
        "/inventory/{skuId}          | post",
        "/inventory/{skuId}/purchase | post"
    })
    @SuppressWarnings("unchecked")
    void unversionedSkuIdPathParametersArePlainStrings(String path, String method) throws Exception {
        Object parameters = map(exportOf(V1), "paths", path, method).get("parameters");
        assertThat(parameters).as("parameters").isInstanceOf(List.class);

        assertThat(((List<Map<String, Object>>) parameters).stream().filter(p -> "skuId".equals(p.get("name"))))
                .singleElement().satisfies(parameter -> {
                    Map<String, Object> schema = map(parameter, "schema");
                    assertThat(schema.get("type")).isEqualTo("string");
                    assertThat(schema).doesNotContainKeys("pattern", "minLength", "maxLength");
                });
    }

    /** G13, V2: the /v2 request quantity is a required 32-bit integer with minimum 1; the body is required JSON. */
    @ParameterizedTest
    @ValueSource(strings = {"/v2/inventory/{skuId}", "/v2/inventory/{skuId}/purchase"})
    void v2RequestQuantityIsInt32WithMinimum1(String path) throws Exception {
        Map<String, Object> doc = exportOf(V2);
        Map<String, Object> requestBody = map(doc, "paths", path, "post", "requestBody");
        Map<String, Object> body = resolve(doc, map(requestBody, "content", MediaType.APPLICATION_JSON_VALUE, "schema"));
        Map<String, Object> quantity = map(body, "properties", "quantity");

        assertThat(requestBody.get("required")).isEqualTo(true);
        assertThat(map(requestBody, "content").keySet()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body.get("required")).isEqualTo(List.of("quantity"));
        assertThat(quantity.get("type")).isEqualTo("integer");
        assertThat(quantity.get("format")).isEqualTo("int32");
        assertThat(quantity.get("minimum")).isInstanceOfSatisfying(Number.class,
                minimum -> assertThat(minimum.intValue()).isEqualTo(1));
    }

    @Test
    void v2PutDetailsBodyIsRequiredJson() throws Exception {
        Map<String, Object> requestBody =
                map(exportOf(V2), "paths", "/v2/inventory/{skuId}/details", "put", "requestBody");

        assertThat(requestBody.get("required")).isEqualTo(true);
        assertThat(map(requestBody, "content").keySet()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
    }

    /** OQ1-B (#8), OD-6: the /v2 operation ids and summaries (the unversioned ones are the spec's). */
    @ParameterizedTest(name = "{1} {0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "/v2/inventory               | get  | listSkus        | List all SKUs with details",
        "/v2/inventory/{skuId}       | get  | getSku          | Get a SKU with its details",
        "/v2/inventory/{skuId}/details | put | putSkuDetails | Create a SKU or replace its details"
    })
    void v2OperationIdsAndSummaries(String path, String method, String operationId, String summary) throws Exception {
        Map<String, Object> operation = map(exportOf(V2), "paths", path, method);

        assertThat(operation.get("operationId")).isEqualTo(operationId);
        assertThat(operation.get("summary")).isEqualTo(summary);
    }

    /** OD-6: the /v2 stock writes have their own operation ids (the front end's generated client names them). */
    @ParameterizedTest(name = "{1} {0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "/v2/inventory/{skuId}          | post | addStock",
        "/v2/inventory/{skuId}/purchase | post | purchaseStock"
    })
    void v2StockWritesHaveOperationIds(String path, String method, String operationId) throws Exception {
        assertThat(map(exportOf(V2), "paths", path, method).get("operationId")).isEqualTo(operationId);
    }

    /** H6: every /v2 body is a SkuItem, the stock writes' 200 included. */
    @ParameterizedTest
    @ValueSource(strings = {"/v2/inventory/{skuId}", "/v2/inventory/{skuId}/purchase"})
    void v2StockWrites200IsASkuItem(String path) throws Exception {
        Map<String, Object> schema = map(exportOf(V2), "paths", path, "post", "responses", "200", "content",
                MediaType.APPLICATION_JSON_VALUE, "schema");

        assertThat(schema.get("$ref")).isEqualTo("#/components/schemas/SkuItem");
    }

    /**
     * R1-4 (#8), H12: every operation is tagged with its group's name and no tag is springdoc's generated
     * "…-controller" name (the tags themselves are an allowed difference from the spec).
     */
    @ParameterizedTest(name = "{0}: {1} operations")
    @CsvSource({V1 + ", 4", V2 + ", 5"})
    @SuppressWarnings("unchecked")
    void operationsAreTaggedWithTheirGroup(String group, int expectedOperations) throws Exception {
        Map<String, Object> doc = exportOf(group);
        Map<String, Object> paths = map(doc, "paths");
        List<String> allTags = new ArrayList<>();
        int checked = 0;
        for (String path : paths.keySet()) {
            Map<String, Object> methods = map(paths, path);
            for (String method : methods.keySet()) {
                Object tags = map(methods, method).get("tags");

                assertThat(tags).as(group + " " + method + " " + path).isEqualTo(List.of(group));
                allTags.addAll((List<String>) tags);
                checked++;
            }
        }
        for (Map<String, Object> tag : (List<Map<String, Object>>) doc.getOrDefault("tags", List.of())) {
            allTags.add((String) tag.get("name"));
        }
        assertThat(checked).as(group + " operations").isEqualTo(expectedOperations);
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
