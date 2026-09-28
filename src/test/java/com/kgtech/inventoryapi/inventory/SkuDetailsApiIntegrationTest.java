package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * Issue #71, DESIGN-V2 §8: the v2 details API end to end against Postgres and Redis. Create with initial stock,
 * 409 on an existing SKU, the validation matrix, keyed creates (201 and 409 replayed), PUT with If-Match (200, 404,
 * 412), v2 reads without the stock cache, the v2 list, and the request guard on v2 paths. Not @Transactional.
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
    static final String SKU_EXISTS = "SKU already exists. Set its details with PUT /v2/inventory/{skuId}; "
            + "add stock with POST /inventory/{skuId}.";
    static final String DETAILS_CHANGED = "Details changed since you read them. Reload the SKU and retry with its "
            + "new ETag.";

    record Reply(int status, String contentType, String body, String etag) {
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

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

    private static String createBody(String details, Integer initialQuantity) {
        return "{\"details\":" + details + (initialQuantity == null ? "" : ",\"initialQuantity\":" + initialQuantity)
                + "}";
    }

    private Reply create(String skuId, String body, String key) throws Exception {
        MockHttpServletRequestBuilder request = post("/v2/inventory/{skuId}", skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return send(request);
    }

    private Reply create(String skuId, int initialQuantity) throws Exception {
        return create(skuId, createBody(DETAILS, initialQuantity), null);
    }

    private Reply replace(String skuId, String details, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = put("/v2/inventory/{skuId}", skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(details);
        if (ifMatch != null) {
            request.header(IF_MATCH, ifMatch);
        }
        return send(request);
    }

    private Reply getV2(String skuId) throws Exception {
        return send(get("/v2/inventory/{skuId}", skuId).accept(APPLICATION_JSON));
    }

    private Reply addV1(String skuId, int quantity, String key) throws Exception {
        MockHttpServletRequestBuilder request = post("/inventory/{skuId}", skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content("{\"quantity\":" + quantity + "}");
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return send(request);
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

    private long skuVersion(String skuId) {
        return count("SELECT version FROM sku WHERE sku_id = ?", skuId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- AC2: create ----

    @Test
    void createWithInitialStockAnswers201WithTheItemAndEtag() throws Exception {
        Reply reply = create("LN-1", 5);

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(reply.etag()).isEqualTo("\"1\"");
        Map<String, Object> item = json(reply);
        assertThat(item).containsEntry("skuId", "LN-1").containsEntry("quantity", 5);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.name")).isEqualTo("Linen shirt");
        assertThat(JsonPath.<String>read(reply.body(), "$.details.description")).isEqualTo("Long sleeve");
        assertThat(JsonPath.<Integer>read(reply.body(), "$.details.cost.amount")).isEqualTo(12900);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.cost.currency")).isEqualTo("USD");
        assertThat(JsonPath.<List<String>>read(reply.body(), "$.details.images"))
                .containsExactly("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg");
        assertThat(quantity("LN-1")).isEqualTo(5);
        assertThat(ledgerRows("LN-1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'add' "
                + "AND quantity_delta = 5", "LN-1")).isEqualTo(1);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void createWithoutInitialStockWritesNoLedgerRow() throws Exception {
        Reply reply = create("LN-0", createBody(OTHER_DETAILS, null), null);

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(json(reply)).containsEntry("quantity", 0);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.description")).isEmpty();
        assertThat(JsonPath.<Map<String, Object>>read(reply.body(), "$.details")).doesNotContainKey("cost");
        assertThat(ledgerRows("LN-0")).isZero();
        assertThat(create("LN-Z", createBody(OTHER_DETAILS, 0), null).status()).isEqualTo(201);
        assertThat(ledgerRows("LN-Z")).isZero();
    }

    // ---- AC3: 409 ----

    @Test
    void createOnAnExistingSkuIs409AndWritesNothing() throws Exception {
        assertThat(create("LN-1", 5).status()).isEqualTo(201);

        Reply again = create("LN-1", createBody(OTHER_DETAILS, 9), null);

        assertText(again, 409, SKU_EXISTS);
        assertThat(quantity("LN-1")).isEqualTo(5);
        assertThat(ledgerRows("LN-1")).isEqualTo(1);
        assertThat(JsonPath.<String>read(getV2("LN-1").body(), "$.details.name")).isEqualTo("Linen shirt");
    }

    @Test
    void createOnASkuTheSpecAddCreatedIs409() throws Exception {
        Tables.seed(jdbc, "V1-1", 3);

        assertText(create("V1-1", 5), 409, SKU_EXISTS);
        assertThat(quantity("V1-1")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", "V1-1")).isZero();
    }

    // ---- AC5: validation ----

    static Stream<Arguments> invalidBodies() {
        String longName = "n".repeat(121);
        String longDescription = "d".repeat(2001);
        String longUrl = "https://cdn.example.com/" + "x".repeat(2049 - 24);
        String elevenImages = String.join(",", java.util.Collections.nCopies(11, "\"https://cdn.example.com/i.jpg\""));
        return Stream.of(
                Arguments.of("no details", "{\"initialQuantity\":1}"),
                Arguments.of("details null", "{\"details\":null}"),
                Arguments.of("no name", createBody("{\"description\":\"x\"}", null)),
                Arguments.of("blank name", createBody("{\"name\":\"  \"}", null)),
                Arguments.of("empty name", createBody("{\"name\":\"\"}", null)),
                Arguments.of("121-char name", createBody("{\"name\":\"" + longName + "\"}", null)),
                Arguments.of("2001-char description", createBody("{\"name\":\"n\",\"description\":\""
                        + longDescription + "\"}", null)),
                Arguments.of("negative amount", createBody("{\"name\":\"n\",\"cost\":{\"amount\":-1,\"currency\":\"USD\"}}",
                        null)),
                Arguments.of("lowercase currency", createBody("{\"name\":\"n\",\"cost\":{\"amount\":1,\"currency\":\"usd\"}}",
                        null)),
                Arguments.of("amount without currency", createBody("{\"name\":\"n\",\"cost\":{\"amount\":1}}", null)),
                Arguments.of("decimal amount", createBody("{\"name\":\"n\",\"cost\":{\"amount\":1.5,\"currency\":\"USD\"}}",
                        null)),
                Arguments.of("11 images", createBody("{\"name\":\"n\",\"images\":[" + elevenImages + "]}", null)),
                Arguments.of("relative image url", createBody("{\"name\":\"n\",\"images\":[\"/a.jpg\"]}", null)),
                Arguments.of("ftp image url", createBody("{\"name\":\"n\",\"images\":[\"ftp://x/a.jpg\"]}", null)),
                Arguments.of("2049-char image url", createBody("{\"name\":\"n\",\"images\":[\"" + longUrl + "\"]}",
                        null)),
                Arguments.of("null image", createBody("{\"name\":\"n\",\"images\":[null]}", null)),
                Arguments.of("NUL in name", createBody("{\"name\":\"a\\u0000b\"}", null)),
                Arguments.of("NUL in description", createBody("{\"name\":\"n\",\"description\":\"a\\u0000b\"}", null)),
                Arguments.of("newline in name", createBody("{\"name\":\"a\\nb\"}", null)),
                Arguments.of("lone surrogate", createBody("{\"name\":\"a\\ud800b\"}", null)),
                Arguments.of("non-ascii image url", createBody("{\"name\":\"n\",\"images\":[\"https://x/\u00fc.jpg\"]}", null)),
                Arguments.of("currency without amount", createBody("{\"name\":\"n\",\"cost\":{\"currency\":\"USD\"}}", null)),
                Arguments.of("negative initial quantity", createBody("{\"name\":\"n\"}", -1)),
                Arguments.of("initial quantity above int32", "{\"details\":{\"name\":\"n\"},\"initialQuantity\":2147483648}"),
                Arguments.of("string initial quantity", "{\"details\":{\"name\":\"n\"},\"initialQuantity\":\"5\"}"),
                Arguments.of("malformed json", "{\"details\":"),
                Arguments.of("empty body", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidCreateBodiesAre400AndWriteNothing(String what, String body) throws Exception {
        assertText(create("NEW-1", body, newKey()), 400, INVALID_REQUEST);
        assertThat(count("SELECT count(*) FROM sku")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys")).as("validation 400s are not stored").isZero();
    }

    @Test
    void unknownPropertiesAreIgnored() throws Exception {
        String body = "{\"details\":{\"name\":\"n\",\"colour\":\"red\"},\"initialQuantity\":1,\"extra\":true}";
        assertThat(create("NEW-2", body, null).status()).isEqualTo(201);
    }

    /** R-07: an explicit null initialQuantity or cost means absent (0, no cost); a newline in a description is text. */
    @Test
    void nullOptionalsMeanAbsentAndDescriptionsMayHoldNewlines() throws Exception {
        Reply reply = create("NEW-3", "{\"details\":{\"name\":\"n\",\"description\":\"line 1\\nline 2\\tend\","
                + "\"cost\":null},\"initialQuantity\":null}", null);

        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(json(reply)).containsEntry("quantity", 0);
        assertThat(JsonPath.<Map<String, Object>>read(reply.body(), "$.details")).doesNotContainKey("cost");
        assertThat(JsonPath.<String>read(reply.body(), "$.details.description")).isEqualTo("line 1\nline 2\tend");
        assertThat(ledgerRows("NEW-3")).isZero();
    }

    @Test
    void malformedSkuIdIs400AndNeverStored() throws Exception {
        assertText(create("bad id", createBody(DETAILS, 1), newKey()), 400, INVALID_REQUEST);
        assertThat(count("SELECT count(*) FROM sku")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys")).isZero();
    }

    // ---- AC4: Idempotency-Key ----

    @Test
    void keyedCreateReplays201ByteForByteAndWritesOnce() throws Exception {
        String key = newKey();
        Reply first = create("K-1", createBody(DETAILS, 4), key);
        Reply replay = create("K-1", createBody(DETAILS, 4), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.contentType()).isEqualTo(first.contentType());
        assertThat(replay.etag()).isEqualTo("\"1\"");
        assertThat(quantity("K-1")).isEqualTo(4);
        assertThat(ledgerRows("K-1")).isEqualTo(1);
        assertThat(redis.<String, String>opsForHash().get("idem:" + key, "status")).as("Redis replay copy")
                .isEqualTo("201");

        // R-06b: tombstone the Postgres row (the README's clean-up); the next replay can only come from Redis.
        jdbc.sql("UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL "
                + "WHERE idempotency_key = ?::uuid").param(key).update();
        Reply fromRedis = create("K-1", createBody(DETAILS, 4), key);
        assertThat(fromRedis.status()).isEqualTo(201);
        assertThat(fromRedis.body()).isEqualTo(first.body());
        assertThat(fromRedis.etag()).isEqualTo("\"1\"");
        assertThat(ledgerRows("K-1")).isEqualTo(1);
    }

    @Test
    void keyed409IsReplayedAndWhitespaceDoesNotChangeTheFingerprint() throws Exception {
        assertThat(create("K-2", 1).status()).isEqualTo(201);
        String key = newKey();
        Reply first = create("K-2", createBody(DETAILS, 2), key);
        Reply replay = create("K-2", "{ \"details\" : " + DETAILS + " , \"initialQuantity\" : 2 , \"x\": 1 }", key);

        assertText(first, 409, SKU_EXISTS);
        assertThat(replay.status()).isEqualTo(409);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.contentType()).isEqualTo(first.contentType());
        assertThat(redis.<String, String>opsForHash().get("idem:" + key, "status")).isEqualTo("409");
        jdbc.sql("UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL "
                + "WHERE idempotency_key = ?::uuid").param(key).update();
        assertText(create("K-2", createBody(DETAILS, 2), key), 409, SKU_EXISTS);
    }

    static Stream<Arguments> differentRequests() {
        return Stream.of(
                Arguments.of("different initial quantity", createBody(DETAILS, 5)),
                Arguments.of("different name", createBody(OTHER_DETAILS, 4)),
                Arguments.of("different cost", createBody(DETAILS.replace("12900", "12901"), 4)),
                Arguments.of("different images", createBody(DETAILS.replace("b.jpg", "c.jpg"), 4)),
                Arguments.of("different description", createBody(DETAILS.replace("Long sleeve", "Short"), 4)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentRequests")
    void sameKeyWithADifferentCreateRequestIs400(String what, String other) throws Exception {
        String key = newKey();
        assertThat(create("K-3", createBody(DETAILS, 4), key).status()).isEqualTo(201);

        assertText(create("K-3", other, key), 400, INVALID_REQUEST);
        assertThat(quantity("K-3")).isEqualTo(4);
    }

    @Test
    void aCreateKeyReusedOnTheSpecAddIs400() throws Exception {
        String key = newKey();
        assertThat(create("K-4", createBody(DETAILS, 4), key).status()).isEqualTo(201);

        assertText(addV1("K-4", 4, key), 400, INVALID_REQUEST);
        assertThat(quantity("K-4")).isEqualTo(4);
        String addKey = newKey();
        assertThat(addV1("K-5", 1, addKey).status()).isEqualTo(200);
        assertText(create("K-5", createBody(DETAILS, 1), addKey), 400, INVALID_REQUEST);
    }

    // ---- AC6: PUT ----

    @Test
    void putReplacesTheDetailsAndBumpsTheEtagWithoutTouchingStock() throws Exception {
        assertThat(create("P-1", 5).status()).isEqualTo(201);
        long version = skuVersion("P-1");

        Reply reply = replace("P-1", OTHER_DETAILS, null);

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.etag()).isEqualTo("\"2\"");
        assertThat(json(reply)).containsEntry("quantity", 5);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.name")).isEqualTo("Linen shirt, navy");
        assertThat(JsonPath.<String>read(reply.body(), "$.details.description")).isEmpty();
        assertThat(JsonPath.<Map<String, Object>>read(reply.body(), "$.details")).doesNotContainKey("cost");
        assertThat(JsonPath.<List<String>>read(reply.body(), "$.details.images")).isEmpty();
        Reply read = getV2("P-1");
        assertThat(read.body()).isEqualTo(reply.body());
        assertThat(read.etag()).isEqualTo("\"2\"");
        assertThat(quantity("P-1")).isEqualTo(5);
        assertThat(skuVersion("P-1")).isEqualTo(version);
        assertThat(ledgerRows("P-1")).isEqualTo(1);
    }

    @Test
    void putCreatesDetailsForASkuTheSpecAddCreated() throws Exception {
        Tables.seed(jdbc, "V1-2", 2);
        assertThat(getV2("V1-2").etag()).isEqualTo("\"0\"");

        Reply reply = replace("V1-2", DETAILS, "\"0\"");

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.etag()).isEqualTo("\"1\"");
        assertThat(json(reply)).containsEntry("quantity", 2);
        assertThat(JsonPath.<String>read(reply.body(), "$.details.name")).isEqualTo("Linen shirt");
    }

    @Test
    void putOnAMissingSkuIs404AndCreatesNothing() throws Exception {
        assertText(replace("NOPE", DETAILS, null), 404, "SKU not found");
        assertText(replace("NOPE", DETAILS, "\"0\""), 404, "SKU not found");
        assertThat(count("SELECT count(*) FROM sku")).isZero();
        assertThat(count("SELECT count(*) FROM sku_details")).isZero();
    }

    @Test
    void putIfMatchDecides() throws Exception {
        assertThat(create("P-2", 1).status()).isEqualTo(201);

        assertThat(replace("P-2", OTHER_DETAILS, "\"1\"").status()).as("current tag").isEqualTo(200);
        assertText(replace("P-2", DETAILS, "\"1\""), 412, DETAILS_CHANGED);
        assertThat(JsonPath.<String>read(getV2("P-2").body(), "$.details.name")).isEqualTo("Linen shirt, navy");
        assertThat(replace("P-2", DETAILS, "\"7\", \"2\"").status()).as("one of several tags").isEqualTo(200);
        assertThat(replace("P-2", OTHER_DETAILS, "*").status()).as("any").isEqualTo(200);
        assertThat(getV2("P-2").etag()).isEqualTo("\"4\"");
        assertText(replace("P-2", DETAILS, "W/\"4\""), 412, DETAILS_CHANGED);
        assertText(replace("P-2", DETAILS, "\"04\""), 412, DETAILS_CHANGED); // R-04: byte-wise strong comparison
        assertText(replace("P-2", DETAILS, "\"abc\""), 412, DETAILS_CHANGED);
        assertText(replace("P-2", DETAILS, "4"), 400, INVALID_REQUEST);
        assertText(replace("P-2", DETAILS, "\"4"), 400, INVALID_REQUEST);
        assertThat(getV2("P-2").etag()).isEqualTo("\"4\"");
    }

    @Test
    void putValidatesLikeCreate() throws Exception {
        assertThat(create("P-3", 1).status()).isEqualTo(201);
        assertText(replace("P-3", "{\"name\":\"\"}", null), 400, INVALID_REQUEST);
        assertText(replace("P-3", "{\"name\":\"n\",\"images\":[\"nope\"]}", null), 400, INVALID_REQUEST);
        assertText(replace("bad id", DETAILS, null), 400, INVALID_REQUEST);
        assertThat(getV2("P-3").etag()).isEqualTo("\"1\"");
    }

    // ---- AC7: reads ----

    @Test
    void getV2ReturnsTheItemWithDetailsAndEtag() throws Exception {
        assertThat(create("G-1", 3).status()).isEqualTo(201);
        assertThat(addV1("G-1", 2, null).status()).isEqualTo(200);

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

    @Test
    void getV2OfAMissingOrMalformedSkuIs404() throws Exception {
        assertText(getV2("NOPE"), 404, "SKU not found");
        assertText(getV2("bad id"), 404, "SKU not found");
    }

    @Test
    void getV2NeverPopulatesTheStockCache() throws Exception {
        assertThat(create("C-1", 3).status()).isEqualTo(201);

        assertThat(getV2("C-1").status()).isEqualTo(200);

        assertThat(redis.hasKey("stock:C-1")).as("v2 reads do not fill the cache").isFalse();
        assertThat(send(get("/inventory/{skuId}", "C-1").accept(APPLICATION_JSON)).status()).isEqualTo(200);
        assertThat(redis.hasKey("stock:C-1")).as("the spec's GET does").isTrue();
    }

    @Test
    void listV2PagesLikeV1AndAgreesRowForRow() throws Exception {
        assertThat(create("L-1", 1).status()).isEqualTo(201);
        Tables.seed(jdbc, "L-2", 2);
        assertThat(create("L-3", createBody(OTHER_DETAILS, 3), null).status()).isEqualTo(201);

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

    @Test
    void guardCoversV2Paths() throws Exception {
        assertThat(create("GD-1", 1).status()).isEqualTo(201);

        assertText(getV2("GD-1;x=y"), 404, "SKU not found");
        assertText(create("GD-1;lot=7", createBody(DETAILS, 1), null), 400, INVALID_REQUEST);
        assertText(replace("GD-1;lot=7", DETAILS, null), 400, INVALID_REQUEST);
        assertText(send(post("/v2/inventory/{skuId}", "GD-2").contentType(APPLICATION_JSON)
                .header(ACCEPT, "application/json;q=0").content(createBody(DETAILS, 1))), 400, INVALID_REQUEST);
        assertText(send(put("/v2/inventory/{skuId}", "GD-1").contentType(APPLICATION_JSON)
                .header(ACCEPT, "application/xml").content(DETAILS)), 400, INVALID_REQUEST);
        assertThat(send(get("/v2/inventory/{skuId}", "GD-1").header(ACCEPT, "application/xml")).status())
                .as("GET ignores Accept").isEqualTo(200);
        assertThat(send(get("/v2/inventory").header(ACCEPT, "text/html")).status()).isEqualTo(200);
        String big = createBody("{\"name\":\"n\",\"description\":\"" + "d".repeat(33_000) + "\"}", null);
        assertText(create("GD-3", big, null), 400, INVALID_REQUEST);
        assertThat(count("SELECT count(*) FROM sku")).isEqualTo(1);
        assertThat(getV2("GD-1").etag()).isEqualTo("\"1\"");
    }

    @Test
    void aBodyNearTheV2CapIsAccepted() throws Exception {
        String images = String.join(",", java.util.Collections.nCopies(10,
                "\"https://cdn.example.com/" + "i".repeat(2000) + "\""));
        String body = createBody("{\"name\":\"n\",\"description\":\"" + "d".repeat(2000) + "\",\"images\":[" + images
                + "]}", 1);
        assertThat(body.length()).isBetween(20_000, 32_768);
        assertThat(create("GD-4", body, null).status()).isEqualTo(201);
    }

    @Test
    void v2WriteWithWrongContentTypeIs400() throws Exception {
        assertText(send(post("/v2/inventory/{skuId}", "CT-1").accept(APPLICATION_JSON)
                .contentType(MediaType.TEXT_PLAIN).content(createBody(DETAILS, 1))), 400, INVALID_REQUEST);
        assertText(send(put("/v2/inventory/{skuId}", "CT-1").accept(APPLICATION_JSON).content(DETAILS)), 400,
                INVALID_REQUEST);
    }
}
