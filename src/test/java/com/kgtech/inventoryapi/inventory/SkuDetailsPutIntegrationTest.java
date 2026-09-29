package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.inventory.SkuDetailsApiIntegrationTest.DETAILS;
import static com.kgtech.inventoryapi.inventory.SkuDetailsApiIntegrationTest.DETAILS_CHANGED;
import static com.kgtech.inventoryapi.inventory.SkuDetailsApiIntegrationTest.INVALID_REQUEST;
import static com.kgtech.inventoryapi.inventory.SkuDetailsApiIntegrationTest.OTHER_DETAILS;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * F-08 (#98), OD-6 and OD-11: {@code PUT /v2/inventory/{skuId}/details} against Postgres. It creates an absent SKU at
 * quantity 0 (201, ETag "1"), replaces the details of an existing one (200), never touches stock, decides
 * If-Match and If-None-Match itself (RFC 9110, 412 text/plain), and ignores Idempotency-Key. Not @Transactional.
 * The old {@code PUT /v2/inventory/{skuId}} keeps its tests in SkuDetailsApiIntegrationTest until it is removed.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SkuDetailsPutIntegrationTest {

    private static final String PATH = "/v2/inventory/{skuId}/details";

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

    private MockHttpServletRequestBuilder putRequest(String skuId, String body) {
        return put(PATH, skuId).accept(APPLICATION_JSON).contentType(APPLICATION_JSON).content(body);
    }

    private Reply putDetails(String skuId, String body) throws Exception {
        return send(putRequest(skuId, body));
    }

    private Reply putDetails(String skuId, String body, String header, String value) throws Exception {
        return send(putRequest(skuId, body).header(header, value));
    }

    private Reply getV2(String skuId) throws Exception {
        return send(get("/v2/inventory/{skuId}", skuId).accept(APPLICATION_JSON));
    }

    private Reply addUnversioned(String skuId, int quantity) throws Exception {
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

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private long ledgerRows(String skuId) {
        return count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", skuId);
    }

    private long quantity(String skuId) {
        return count("SELECT quantity FROM sku WHERE sku_id = ?", skuId);
    }

    private String detailsName(String skuId) throws Exception {
        return JsonPath.read(getV2(skuId).body(), "$.details.name");
    }

    private void assertNothingStored() {
        assertThat(count("SELECT count(*) FROM sku")).as("sku rows").isZero();
        assertThat(count("SELECT count(*) FROM sku_details")).as("sku_details rows").isZero();
        assertThat(count("SELECT count(*) FROM inventory_ledger")).as("ledger rows").isZero();
    }

    // ---- create (absent SKU) ----

    @Test
    void anAbsentSkuIsCreatedAtQuantityZeroWith201AndEtagOne() throws Exception {
        Reply reply = putDetails("PD-1", DETAILS);

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(MediaType.parseMediaType(reply.contentType()).isCompatibleWith(APPLICATION_JSON)).isTrue();
        assertThat(reply.etag()).isEqualTo("\"1\"");
        Map<String, Object> item = JsonPath.read(reply.body(), "$");
        assertThat(item).containsEntry("skuId", "PD-1").containsEntry("quantity", 0);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.name")).isEqualTo("Linen shirt");
        assertThat(JsonPath.<Integer>read(reply.body(), "$.details.cost.amount")).isEqualTo(12900);
        assertThat(JsonPath.<List<String>>read(reply.body(), "$.details.images")).hasSize(2);
        assertThat(ledgerRows("PD-1")).isZero();
        assertThat(quantity("PD-1")).isZero();
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", "PD-1")).isEqualTo(1);
        assertThat(getV2("PD-1").etag()).isEqualTo("\"1\"");
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    // ---- replace (existing SKU) ----

    @Test
    void anExistingSkuHasItsDetailsReplacedAndStockLeftAlone() throws Exception {
        assertThat(addUnversioned("PD-2", 5).status()).isEqualTo(200);
        assertThat(getV2("PD-2").etag()).isEqualTo("\"0\"");

        Reply first = putDetails("PD-2", DETAILS);
        Reply second = putDetails("PD-2", OTHER_DETAILS);

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(first.etag()).isEqualTo("\"1\"");
        assertThat(JsonPath.<Integer>read(first.body(), "$.quantity")).isEqualTo(5);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.etag()).isEqualTo("\"2\"");
        assertThat(JsonPath.<String>read(second.body(), "$.details.name")).isEqualTo("Linen shirt, navy");
        assertThat(quantity("PD-2")).isEqualTo(5);
        assertThat(ledgerRows("PD-2")).isEqualTo(1);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    /** The details PUT never changes stock, and both versions of the API agree on the one SKU. */
    @Test
    void stockAddedThroughTheSpecPostSurvivesAPutAndBothVersionsAgree() throws Exception {
        assertThat(addUnversioned("PD-3", 5).status()).isEqualTo(200);

        Reply put = putDetails("PD-3", DETAILS);

        Reply read = getV2("PD-3");
        assertThat(JsonPath.<Integer>read(read.body(), "$.quantity")).isEqualTo(5);
        assertThat(read.etag()).isEqualTo(put.etag());
        MockHttpServletResponse v2 = mvc.perform(get("/v2/inventory/{skuId}", "PD-3").accept(APPLICATION_JSON))
                .andReturn().getResponse();
        assertThat(v2.getHeader(CACHE_CONTROL)).isEqualTo("no-store");
        MockHttpServletResponse v1 = mvc.perform(get("/inventory/{skuId}", "PD-3").accept(APPLICATION_JSON))
                .andReturn().getResponse();
        assertThat(JsonPath.<Integer>read(v1.getContentAsString(), "$.quantity")).isEqualTo(5);
    }

    // ---- If-Match ----

    @Test
    void ifMatchDecidesOnAnExistingSku() throws Exception {
        assertThat(putDetails("PM-1", DETAILS).status()).isEqualTo(201);

        assertThat(putDetails("PM-1", OTHER_DETAILS, IF_MATCH, "\"1\"").status()).as("current tag").isEqualTo(200);
        assertText(putDetails("PM-1", DETAILS, IF_MATCH, "\"1\""), 412, DETAILS_CHANGED);
        assertThat(detailsName("PM-1")).isEqualTo("Linen shirt, navy");
        assertThat(putDetails("PM-1", DETAILS, IF_MATCH, "\"7\", \"2\"").status()).as("one of several").isEqualTo(200);
        assertThat(putDetails("PM-1", OTHER_DETAILS, IF_MATCH, "*").status()).as("any existing SKU").isEqualTo(200);
        assertThat(getV2("PM-1").etag()).isEqualTo("\"4\"");
        assertText(putDetails("PM-1", DETAILS, IF_MATCH, "W/\"4\""), 412, DETAILS_CHANGED);
        assertText(putDetails("PM-1", DETAILS, IF_MATCH, "\"04\""), 412, DETAILS_CHANGED);
        assertText(putDetails("PM-1", DETAILS, IF_MATCH, "\"abc\""), 412, DETAILS_CHANGED);
        assertThat(getV2("PM-1").etag()).isEqualTo("\"4\"");
    }

    @Test
    void ifMatchZeroMatchesASkuWithoutDetails() throws Exception {
        Tables.seed(jdbc, "PM-2", 2);

        Reply reply = putDetails("PM-2", DETAILS, IF_MATCH, "\"0\"");

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.etag()).isEqualTo("\"1\"");
        assertText(putDetails("PM-2", OTHER_DETAILS, IF_MATCH, "\"0\""), 412, DETAILS_CHANGED);
    }

    /** RFC 9110 §13.1.1: If-Match, even "*", fails when there is no current representation; nothing is created. */
    @ParameterizedTest(name = "If-Match: {0}")
    @ValueSource(strings = {"*", "\"0\"", "\"1\"", "W/\"1\"", "\"7\", \"2\""})
    void anyIfMatchOnAnAbsentSkuIs412AndCreatesNothing(String ifMatch) throws Exception {
        assertText(putDetails("PM-3", DETAILS, IF_MATCH, ifMatch), 412, DETAILS_CHANGED);

        assertNothingStored();
    }

    @ParameterizedTest(name = "malformed If-Match: {0}")
    @ValueSource(strings = {"4", "\"4", "", "\"4\" \"5\"", "\"4\", *"})
    void aMalformedIfMatchIs400OnAnExistingAndAnAbsentSku(String ifMatch) throws Exception {
        assertThat(putDetails("PM-4", DETAILS).status()).isEqualTo(201);

        assertText(putDetails("PM-4", OTHER_DETAILS, IF_MATCH, ifMatch), 400, INVALID_REQUEST);
        assertText(putDetails("PM-5", DETAILS, IF_MATCH, ifMatch), 400, INVALID_REQUEST);

        assertThat(detailsName("PM-4")).isEqualTo("Linen shirt");
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", "PM-5")).isZero();
    }

    // ---- If-None-Match (OD-11) ----

    @Test
    void ifNoneMatchStarCreatesAnAbsentSku() throws Exception {
        Reply reply = putDetails("PN-1", DETAILS, IF_NONE_MATCH, "*");

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(reply.etag()).isEqualTo("\"1\"");
        assertThat(quantity("PN-1")).isZero();
        assertThat(ledgerRows("PN-1")).isZero();
    }

    /** With details, without details, and created by the spec's add: all exist, so all answer 412 and change nothing. */
    @Test
    void ifNoneMatchStarOnAnExistingSkuIs412AndChangesNothing() throws Exception {
        assertThat(putDetails("PN-2", DETAILS).status()).isEqualTo(201);
        Tables.seed(jdbc, "PN-3", 4);

        assertText(putDetails("PN-2", OTHER_DETAILS, IF_NONE_MATCH, "*"), 412, DETAILS_CHANGED);
        assertText(putDetails("PN-3", OTHER_DETAILS, IF_NONE_MATCH, "*"), 412, DETAILS_CHANGED);

        assertThat(detailsName("PN-2")).isEqualTo("Linen shirt");
        assertThat(getV2("PN-2").etag()).isEqualTo("\"1\"");
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", "PN-3")).isZero();
        assertThat(quantity("PN-3")).isEqualTo(4);
    }

    /** Only "*" is supported (OD-11); any tag list, weak tag or empty value is a bad request, not a precondition. */
    @ParameterizedTest(name = "If-None-Match: [{0}]")
    @ValueSource(strings = {"\"1\"", "W/\"1\"", "1", "", "\"1\", *", "*, \"1\"", "**"})
    void anIfNoneMatchOtherThanStarIs400(String ifNoneMatch) throws Exception {
        assertThat(putDetails("PN-4", DETAILS).status()).isEqualTo(201);

        assertText(putDetails("PN-4", OTHER_DETAILS, IF_NONE_MATCH, ifNoneMatch), 400, INVALID_REQUEST);
        assertText(putDetails("PN-5", DETAILS, IF_NONE_MATCH, ifNoneMatch), 400, INVALID_REQUEST);

        assertThat(detailsName("PN-4")).isEqualTo("Linen shirt");
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", "PN-5")).isZero();
    }

    // ---- both conditional headers together ----

    /** A malformed If-Match is 400 whatever else is sent; If-None-Match: * must not swallow it. */
    @ParameterizedTest(name = "malformed If-Match [{0}] with If-None-Match: *")
    @ValueSource(strings = {"abc", "\"4", ""})
    void aMalformedIfMatchWithIfNoneMatchStarIs400OnAnAbsentAndAnExistingSku(String ifMatch) throws Exception {
        assertThat(putDetails("PB-1", DETAILS).status()).isEqualTo(201);

        assertText(send(putRequest("PB-1", OTHER_DETAILS).header(IF_MATCH, ifMatch).header(IF_NONE_MATCH, "*")), 400,
                INVALID_REQUEST);
        assertText(send(putRequest("PB-2", DETAILS).header(IF_MATCH, ifMatch).header(IF_NONE_MATCH, "*")), 400,
                INVALID_REQUEST);

        assertThat(detailsName("PB-1")).isEqualTo("Linen shirt");
        assertThat(getV2("PB-1").etag()).isEqualTo("\"1\"");
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", "PB-2")).isZero();
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", "PB-2")).isZero();
    }

    /** Documents today's behaviour: a well-formed If-Match together with If-None-Match: * always fails (412). */
    @ParameterizedTest(name = "If-Match [{0}] with If-None-Match: *")
    @ValueSource(strings = {"\"1\"", "*"})
    void aValidIfMatchWithIfNoneMatchStarIs412OnAnAbsentAndAnExistingSku(String ifMatch) throws Exception {
        assertThat(putDetails("PB-3", DETAILS).status()).isEqualTo(201);

        assertText(send(putRequest("PB-3", OTHER_DETAILS).header(IF_MATCH, ifMatch).header(IF_NONE_MATCH, "*")), 412,
                DETAILS_CHANGED);
        assertText(send(putRequest("PB-4", DETAILS).header(IF_MATCH, ifMatch).header(IF_NONE_MATCH, "*")), 412,
                DETAILS_CHANGED);

        assertThat(detailsName("PB-3")).isEqualTo("Linen shirt");
        assertThat(getV2("PB-3").etag()).isEqualTo("\"1\"");
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", "PB-4")).isZero();
    }

    // ---- repeated header lines ----

    /** Every If-Match line counts, as one list (RFC 9110 §5.3); a repeated If-None-Match is not "*", so 400. */
    @Test
    void repeatedIfMatchLinesAreOneListAndRepeatedIfNoneMatchLinesAre400() throws Exception {
        assertThat(putDetails("PB-5", DETAILS).status()).isEqualTo(201);

        assertThat(send(putRequest("PB-5", OTHER_DETAILS).header(IF_MATCH, "\"7\"", "\"1\"")).status())
                .as("one of the tags on separate lines").isEqualTo(200);
        assertText(send(putRequest("PB-5", DETAILS).header(IF_MATCH, "\"7\"", "\"8\"")), 412, DETAILS_CHANGED);
        assertText(send(putRequest("PB-5", DETAILS).header(IF_MATCH, "\"2\"", "abc")), 400, INVALID_REQUEST);
        assertText(send(putRequest("PB-5", DETAILS).header(IF_NONE_MATCH, "*", "*")), 400, INVALID_REQUEST);
        assertText(send(putRequest("PB-6", DETAILS).header(IF_NONE_MATCH, "*", "*")), 400, INVALID_REQUEST);

        assertThat(detailsName("PB-5")).isEqualTo("Linen shirt, navy");
        assertThat(getV2("PB-5").etag()).isEqualTo("\"2\"");
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", "PB-6")).isZero();
    }

    // ---- validation ----

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("malformed json", "{\"name\":"),
                Arguments.of("empty body", ""),
                Arguments.of("missing name", "{\"description\":\"x\"}"),
                Arguments.of("blank name", "{\"name\":\"  \"}"),
                Arguments.of("wrong type", "{\"name\":123}"),
                Arguments.of("bad image url", "{\"name\":\"n\",\"images\":[\"nope\"]}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void anInvalidBodyIs400AndWritesNothing(String what, String body) throws Exception {
        assertText(putDetails("PV-1", body), 400, INVALID_REQUEST);
        assertNothingStored();

        assertThat(putDetails("PV-2", DETAILS).status()).isEqualTo(201);
        assertText(putDetails("PV-2", body), 400, INVALID_REQUEST);
        assertThat(getV2("PV-2").etag()).isEqualTo("\"1\"");
    }

    @Test
    void unknownPropertiesAreIgnored() throws Exception {
        assertThat(putDetails("PV-3", "{\"name\":\"n\",\"colour\":\"red\"}").status()).isEqualTo(201);
    }

    @ParameterizedTest(name = "skuId [{0}]")
    @ValueSource(strings = {"-a", "bad id", "PV;lot=7", "PV%3Blot=7", "a%2Fb", "_a", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void anInvalidSkuIdIs400AndWritesNothing(String skuId) throws Exception {
        String uri = "/v2/inventory/" + skuId + "/details";
        Reply reply = send(put(java.net.URI.create(uri.replace(" ", "%20"))).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(DETAILS));

        assertText(reply, 400, INVALID_REQUEST);
        assertNothingStored();
    }

    /** U3 order: a bad body and a bad skuId are both 400 with the same text, whichever is checked first. */
    @Test
    void anInvalidSkuIdWithAnInvalidBodyIs400() throws Exception {
        assertText(putDetails("-a", "{\"name\":"), 400, INVALID_REQUEST);
        assertNothingStored();
    }

    @Test
    void aWrongContentTypeIs400() throws Exception {
        assertText(send(put(PATH, "PV-4").accept(APPLICATION_JSON).contentType(MediaType.TEXT_PLAIN).content(DETAILS)),
                400, INVALID_REQUEST);
        assertText(send(put(PATH, "PV-4").accept(APPLICATION_JSON).content(DETAILS)), 400, INVALID_REQUEST);
        assertNothingStored();
    }

    // ---- Idempotency-Key is ignored ----

    @ParameterizedTest(name = "Idempotency-Key: [{0}]")
    @ValueSource(strings = {"a3a0f0b2-6a4f-4d41-9f0e-6f6b2f1c9d10", "not-a-uuid", ""})
    void anIdempotencyKeyIsIgnoredAndNothingIsStored(String key) throws Exception {
        Reply created = send(putRequest("PK-1", DETAILS).header(IDEMPOTENCY_KEY, key));
        Reply again = send(putRequest("PK-1", DETAILS).header(IDEMPOTENCY_KEY, key));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(again.status()).as("not a replay of the stored 201").isEqualTo(200);
        assertThat(again.etag()).isEqualTo("\"2\"");
        assertThat(count("SELECT count(*) FROM idempotency_keys")).isZero();
    }

    // ---- Accept ----

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/xml", "application/json;q=0", "text/html"})
    void anAcceptThatExcludesJsonIs400AndWritesNothing(String accept) throws Exception {
        assertText(send(put(PATH, "PA-1").contentType(APPLICATION_JSON).header(ACCEPT, accept).content(DETAILS)),
                400, INVALID_REQUEST);
        assertNothingStored();

        assertThat(putDetails("PA-2", DETAILS).status()).isEqualTo(201);
        assertText(send(put(PATH, "PA-2").contentType(APPLICATION_JSON).header(ACCEPT, accept)
                .content(OTHER_DETAILS)), 400, INVALID_REQUEST);
        assertThat(getV2("PA-2").etag()).isEqualTo("\"1\"");
    }

    /** Spring must not answer the conditional headers itself (its 412 or 304 would skip the service's decision). */
    @Test
    void theServiceDecidesTheConditionalHeadersNotSpring() throws Exception {
        Reply created = putDetails("PS-1", DETAILS, IF_NONE_MATCH, "*");
        Reply replaced = putDetails("PS-1", OTHER_DETAILS, IF_MATCH, "\"1\"");

        assertThat(created.status()).isEqualTo(201);
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(replaced.body()).isNotEmpty();
        assertText(putDetails("PS-1", DETAILS, IF_NONE_MATCH, "*"), 412, DETAILS_CHANGED);
    }
}
