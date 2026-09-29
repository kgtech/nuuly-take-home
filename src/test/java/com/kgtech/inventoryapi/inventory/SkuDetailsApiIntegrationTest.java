package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * Issue #71, DESIGN-V2 §8, OD-6: the v2 details API end to end against Postgres: the details validation matrix on
 * PUT .../details, the v2 reads, the v2 list, and the request guard and body cap on the v2 routes. The conditional
 * PUT is SkuDetailsPutIntegrationTest's; the /v2 add and purchase are V2WritesApiIntegrationTest's. Not
 * @Transactional.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SkuDetailsApiIntegrationTest {

    static final String DETAILS = """
            {"name":"Linen shirt","description":"Long sleeve","cost":{"amount":12900,"currency":"USD"},
             "images":["https://cdn.example.com/a.jpg","https://cdn.example.com/b.jpg"]}
            """;
    static final String OTHER_DETAILS = "{\"name\":\"Linen shirt, navy\",\"images\":[]}";
    static final String INVALID_REQUEST = "Invalid request";
    static final String DETAILS_CHANGED = "Details changed since you read them. Reload the SKU and retry with its "
            + "new ETag.";

    record Reply(int status, String contentType, String body, String etag) {
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    // ---- helpers ----

    private Reply send(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getHeader(HttpHeaders.CONTENT_TYPE),
                response.getContentAsString(), response.getHeader(ETAG));
    }

    private Reply putDetails(String skuId, String details) throws Exception {
        return send(put("/v2/inventory/{skuId}/details", skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(details));
    }

    private Reply getV2(String skuId) throws Exception {
        return send(get("/v2/inventory/{skuId}", skuId).accept(APPLICATION_JSON));
    }

    private Reply addV1(String skuId, int quantity) throws Exception {
        return send(post("/inventory/{skuId}", skuId).accept(APPLICATION_JSON).contentType(APPLICATION_JSON)
                .content("{\"quantity\":" + quantity + "}"));
    }

    private static void assertText(Reply reply, int status, String body) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(reply.contentType()).isCompatibleWith(MediaType.TEXT_PLAIN))
                .as(reply.contentType()).isTrue();
        assertThat(reply.body()).isEqualTo(body);
        assertThat(reply.etag()).as("no ETag on an error").isNull();
    }

    private static Map<String, Object> json(Reply reply) {
        assertThat(MediaType.parseMediaType(reply.contentType()).isCompatibleWith(APPLICATION_JSON))
                .as(reply.contentType()).isTrue();
        return JsonPath.read(reply.body(), "$");
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private long ledgerRows(String skuId) {
        return count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", skuId);
    }

    private long quantity(String skuId) {
        return count("SELECT quantity FROM sku WHERE sku_id = ?", skuId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** A SKU the way the UI leaves it: stock recorded, then details set (200: the SKU exists, so the PUT replaces). */
    private void withDetails(String skuId, long quantity) throws Exception {
        Tables.seed(jdbc, skuId, quantity);
        assertThat(putDetails(skuId, DETAILS).status()).isEqualTo(200);
    }

    // ---- AC5: details validation (one matrix; the same body rules apply to every route that takes details) ----

    static Stream<Arguments> invalidBodies() {
        String longName = "n".repeat(121);
        String longDescription = "d".repeat(2001);
        String longUrl = "https://cdn.example.com/" + "x".repeat(2049 - 24);
        String elevenImages = String.join(",", java.util.Collections.nCopies(11, "\"https://cdn.example.com/i.jpg\""));
        return Stream.of(
                Arguments.of("no name", "{\"description\":\"x\"}"),
                Arguments.of("blank name", "{\"name\":\"  \"}"),
                Arguments.of("empty name", "{\"name\":\"\"}"),
                Arguments.of("121-char name", "{\"name\":\"" + longName + "\"}"),
                Arguments.of("2001-char description", "{\"name\":\"n\",\"description\":\"" + longDescription + "\"}"),
                Arguments.of("negative amount", "{\"name\":\"n\",\"cost\":{\"amount\":-1,\"currency\":\"USD\"}}"),
                Arguments.of("lowercase currency", "{\"name\":\"n\",\"cost\":{\"amount\":1,\"currency\":\"usd\"}}"),
                Arguments.of("amount without currency", "{\"name\":\"n\",\"cost\":{\"amount\":1}}"),
                Arguments.of("decimal amount", "{\"name\":\"n\",\"cost\":{\"amount\":1.5,\"currency\":\"USD\"}}"),
                Arguments.of("11 images", "{\"name\":\"n\",\"images\":[" + elevenImages + "]}"),
                Arguments.of("relative image url", "{\"name\":\"n\",\"images\":[\"/a.jpg\"]}"),
                Arguments.of("ftp image url", "{\"name\":\"n\",\"images\":[\"ftp://x/a.jpg\"]}"),
                Arguments.of("2049-char image url", "{\"name\":\"n\",\"images\":[\"" + longUrl + "\"]}"),
                Arguments.of("null image", "{\"name\":\"n\",\"images\":[null]}"),
                Arguments.of("NUL in name", "{\"name\":\"a\\u0000b\"}"),
                Arguments.of("C1 control in name", "{\"name\":\"a\\u0085b\"}"),
                Arguments.of("C1 control in description", "{\"name\":\"n\",\"description\":\"a\\u009fb\"}"),
                Arguments.of("numeric name", "{\"name\":123}"),
                Arguments.of("boolean name", "{\"name\":true}"),
                Arguments.of("numeric description", "{\"name\":\"n\",\"description\":42}"),
                Arguments.of("numeric image", "{\"name\":\"n\",\"images\":[1]}"),
                Arguments.of("numeric currency", "{\"name\":\"n\",\"cost\":{\"amount\":1,\"currency\":840}}"),
                Arguments.of("NUL in description", "{\"name\":\"n\",\"description\":\"a\\u0000b\"}"),
                Arguments.of("newline in name", "{\"name\":\"a\\nb\"}"),
                Arguments.of("lone surrogate", "{\"name\":\"a\\ud800b\"}"),
                Arguments.of("non-ascii image url", "{\"name\":\"n\",\"images\":[\"https://x/\u00fc.jpg\"]}"),
                Arguments.of("currency without amount", "{\"name\":\"n\",\"cost\":{\"currency\":\"USD\"}}"),
                Arguments.of("details wrapped in an object", "{\"details\":{\"name\":\"n\"}}"),
                Arguments.of("malformed json", "{\"name\":"),
                Arguments.of("empty body", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidDetailsBodiesAre400AndWriteNothing(String what, String body) throws Exception {
        assertText(putDetails("NEW-1", body), 400, INVALID_REQUEST);
        assertThat(count("SELECT count(*) FROM sku")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys")).isZero();
    }

    /** R-07: an explicit null cost means absent; a newline in a description is text; no stock is recorded. */
    @Test
    void nullOptionalsMeanAbsentAndDescriptionsMayHoldNewlines() throws Exception {
        Reply reply = putDetails("NEW-3", "{\"name\":\"n\",\"description\":\"line 1\\nline 2\\tend\",\"cost\":null}");

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(json(reply)).containsEntry("quantity", 0);
        assertThat(JsonPath.<Map<String, Object>>read(reply.body(), "$.details")).doesNotContainKey("cost");
        assertThat(JsonPath.<String>read(reply.body(), "$.details.description")).isEqualTo("line 1\nline 2\tend");
        assertThat(ledgerRows("NEW-3")).isZero();
    }

    // ---- AC7: reads ----

    @Test
    void getV2ReturnsTheItemWithDetailsAndEtag() throws Exception {
        withDetails("G-1", 3);
        assertThat(addV1("G-1", 2).status()).isEqualTo(200);

        Reply reply = getV2("G-1");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.etag()).isEqualTo("\"1\"");
        assertThat(json(reply)).containsEntry("skuId", "G-1").containsEntry("quantity", 5).containsKey("details");
    }

    @Test
    void getV2OfASkuWithoutDetailsHasNoDetailsPropertyAndEtagZero() throws Exception {
        Tables.seed(jdbc, "V1-3", 7);

        Reply reply = getV2("V1-3");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.etag()).isEqualTo("\"0\"");
        assertThat(json(reply)).containsEntry("skuId", "V1-3").containsEntry("quantity", 7).doesNotContainKey("details");
    }

    /**
     * DESIGN-V2 §8: the ETag validates details for If-Match only. A GET with a matching If-None-Match is answered
     * 200 with the current count, never 304 (Spring's ResponseEntity handling would otherwise send one and a browser
     * would keep a cached, stale quantity after a purchase); item and list responses are Cache-Control: no-store.
     */
    @Test
    void getV2IgnoresIfNoneMatchAndIsNeverStored() throws Exception {
        withDetails("NM-1", 10);
        Reply first = getV2("NM-1");
        assertThat(first.etag()).isEqualTo("\"1\"");
        assertThat(send(post("/inventory/{skuId}/purchase", "NM-1").accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content("{\"quantity\":3}")).status()).isEqualTo(200);

        MockHttpServletResponse conditional = mvc.perform(get("/v2/inventory/{skuId}", "NM-1")
                .accept(APPLICATION_JSON).header(HttpHeaders.IF_NONE_MATCH, "\"1\"")
                .header(HttpHeaders.IF_MODIFIED_SINCE, "Sun, 06 Nov 2094 08:49:37 GMT")).andReturn().getResponse();

        assertThat(conditional.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(conditional.getContentAsString(), "$.quantity")).isEqualTo(7);
        assertThat(conditional.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(first.etag()).isEqualTo(conditional.getHeader(ETAG));
        MockHttpServletResponse list = mvc.perform(get("/v2/inventory").accept(APPLICATION_JSON)
                .header(HttpHeaders.IF_NONE_MATCH, "*")).andReturn().getResponse();
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(list.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        MockHttpServletResponse created = mvc.perform(put("/v2/inventory/{skuId}/details", "NM-3")
                .accept(APPLICATION_JSON).contentType(APPLICATION_JSON).content(DETAILS)).andReturn().getResponse();
        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(created.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void getV2OfAMissingOrMalformedSkuIs404() throws Exception {
        assertText(getV2("NOPE"), 404, "SKU not found");
        assertText(getV2("bad id"), 404, "SKU not found");
    }

    @Test
    void listV2PagesLikeV1AndAgreesRowForRow() throws Exception {
        withDetails("L-1", 1);
        Tables.seed(jdbc, "L-2", 2);
        Tables.seed(jdbc, "L-3", 3);
        assertThat(putDetails("L-3", OTHER_DETAILS).status()).isEqualTo(200);

        MockHttpServletResponse page = mvc.perform(get("/v2/inventory").queryParam("limit", "2")
                .accept(APPLICATION_JSON)).andReturn().getResponse();

        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getHeader(LINK)).isEqualTo("<http://localhost/v2/inventory?limit=2&after=L-2>; rel=\"next\"");
        List<Map<String, Object>> items = JsonPath.read(page.getContentAsString(), "$");
        assertThat(items).extracting(i -> i.get("skuId")).containsExactly("L-1", "L-2");
        assertThat(items.get(0)).containsKey("details");
        assertThat(items.get(1)).doesNotContainKey("details");

        MockHttpServletResponse next = mvc.perform(get("/v2/inventory").queryParam("limit", "2")
                .queryParam("after", "L-2").accept(APPLICATION_JSON)).andReturn().getResponse();
        assertThat(next.getHeader(LINK)).isNull();
        assertThat(JsonPath.<List<String>>read(next.getContentAsString(), "$[*].skuId")).containsExactly("L-3");

        List<Map<String, Object>> v1 = JsonPath.read(mvc.perform(get("/inventory").accept(APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(), "$");
        List<Map<String, Object>> v2 = JsonPath.read(mvc.perform(get("/v2/inventory").accept(APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(), "$");
        assertThat(v2).hasSize(3);
        for (int i = 0; i < v1.size(); i++) {
            assertThat(v2.get(i).get("skuId")).isEqualTo(v1.get(i).get("skuId"));
            assertThat(v2.get(i).get("quantity")).isEqualTo(v1.get(i).get("quantity"));
        }
    }

    /** C2 for v2 (R-06c): more than 250 SKUs page at 250 with a Link on /v2/inventory; following it visits every SKU. */
    @Test
    void listV2DefaultsTo250AndTheLinkVisitsEverySku() throws Exception {
        for (int i = 0; i < 251; i++) {
            jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(String.format("P-%03d", i)).update();
        }
        MockHttpServletResponse first = mvc.perform(get("/v2/inventory").accept(APPLICATION_JSON)).andReturn()
                .getResponse();
        List<String> ids = JsonPath.read(first.getContentAsString(), "$[*].skuId");
        assertThat(ids).hasSize(250);
        assertThat(first.getHeader(LINK)).isEqualTo("<http://localhost/v2/inventory?limit=250&after=P-249>; rel=\"next\"");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<([^>]+)>").matcher(first.getHeader(LINK));
        assertThat(m.find()).isTrue();
        MockHttpServletResponse second = mvc.perform(get(java.net.URI.create(m.group(1))).accept(APPLICATION_JSON))
                .andReturn().getResponse();
        assertThat(JsonPath.<List<String>>read(second.getContentAsString(), "$[*].skuId")).containsExactly("P-250");
        assertThat(second.getHeader(LINK)).isNull();
    }

    @Test
    void listV2RejectsARepeatedAfter() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/v2/inventory").queryParam("after", "a", "b")
                .accept(APPLICATION_JSON)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEqualTo(INVALID_REQUEST);
    }

    // ---- AC8: request guard on v2 ----

    private Reply addV2(String skuId, String body, String accept) throws Exception {
        return send(post("/v2/inventory/{skuId}", skuId).contentType(APPLICATION_JSON).header(ACCEPT, accept)
                .header(IDEMPOTENCY_KEY, newKey()).content(body));
    }

    @Test
    void guardCoversV2Paths() throws Exception {
        withDetails("GD-1", 1);

        assertText(getV2("GD-1;x=y"), 404, "SKU not found");
        assertText(putDetails("GD-1;lot=7", DETAILS), 400, INVALID_REQUEST);
        assertText(addV2("GD-1;lot=7", "{\"quantity\":1}", "application/json"), 400, INVALID_REQUEST);
        assertText(addV2("GD-2", "{\"quantity\":1}", "application/json;q=0"), 400, INVALID_REQUEST);
        assertText(send(put("/v2/inventory/{skuId}/details", "GD-1").contentType(APPLICATION_JSON)
                .header(ACCEPT, "application/xml").content(DETAILS)), 400, INVALID_REQUEST);
        assertThat(send(get("/v2/inventory/{skuId}", "GD-1").header(ACCEPT, "application/xml")).status())
                .as("GET ignores Accept").isEqualTo(200);
        assertThat(send(get("/v2/inventory").header(ACCEPT, "text/html")).status()).isEqualTo(200);
        String big = "{\"name\":\"n\",\"pad\":\"" + "d".repeat(66_000) + "\"}";
        assertText(putDetails("GD-3", big), 400, INVALID_REQUEST);
        assertThat(count("SELECT count(*) FROM sku")).isEqualTo(1);
        assertThat(getV2("GD-1").etag()).isEqualTo("\"1\"");
        assertThat(count("SELECT count(*) FROM idempotency_keys")).isZero();
    }

    /** The details PUT keeps the 64 KB cap; the /v2 POSTs are capped at 4 KB like the spec's (InventoryRequestGuardIntegrationTest). */
    @Test
    void aLargeAsciiBodyUnderTheCapIsAccepted() throws Exception {
        String images = String.join(",", java.util.Collections.nCopies(10,
                "\"https://cdn.example.com/" + "i".repeat(2000) + "\""));
        String body = "{\"name\":\"n\",\"description\":\"" + "d".repeat(2000) + "\",\"images\":[" + images + "]}";
        assertThat(body.length()).isBetween(20_000, 65_536);
        assertThat(putDetails("GD-4", body).status()).isEqualTo(201);
    }

    /**
     * Critique F-conc-01: the largest contract-valid body with every non-ASCII character escaped as \\uXXXX (about
     * 33.4 KB on the wire) must be accepted; the byte cap is sized for it.
     */
    @Test
    void theLargestEscapedValidBodyIsAccepted() throws Exception {
        String name = "\\u00e9".repeat(120);
        String description = "\\u65e5".repeat(2000);
        String images = String.join(",", java.util.Collections.nCopies(10,
                "\"https://cdn.example.com/" + "i".repeat(2048 - 24) + "\""));
        String body = "{\"name\":\"" + name + "\",\"description\":\"" + description + "\",\"cost\":"
                + "{\"amount\":9223372036854775807,\"currency\":\"USD\"},\"images\":[" + images + "]}";
        assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isBetween(33_000, 65_536);

        Reply reply = putDetails("GD-5", body);

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.name")).hasSize(120);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.description")).hasSize(2000);
        assertThat(JsonPath.<List<String>>read(reply.body(), "$.details.images")).hasSize(10);
    }
}
