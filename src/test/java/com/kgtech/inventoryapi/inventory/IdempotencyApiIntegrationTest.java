package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * Issue #6 end to end against Postgres: Idempotency-Key claim, replay, mismatch, expiry and what is never stored
 * (G8, G14, R1, R2, S2, S3, S8, T1, U3, Y1, Y3, Y4, A18, A33, A34). Not @Transactional: every request commits its own
 * transaction, so the tables are reset before each test. Same annotations as InventoryApiIntegrationTest so the
 * context and container are reused.
 */
@IntegrationTest
@AutoConfigureMockMvc
class IdempotencyApiIntegrationTest {

    private static final String INVALID_REQUEST = "Invalid request";

    /** The two keyed POST operations. */
    enum Post {
        CREATE("/inventory/{skuId}"),
        PURCHASE("/inventory/{skuId}/purchase");

        final String path;

        Post(String path) {
            this.path = path;
        }
    }

    /** One HTTP response: status, the exact Content-Type header and the body. */
    private record Reply(int status, String contentType, String body) {
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private LedgerFaultTrigger fault;

    @BeforeEach
    void cleanTables() {
        fault = new LedgerFaultTrigger(jdbcTemplate);
        fault.drop(); // in case an earlier run was killed before its @AfterEach
        Tables.reset(jdbc);
    }

    @AfterEach
    void dropTrigger() {
        fault.drop();
    }

    // ---- helpers ----

    private Reply send(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getHeader(HttpHeaders.CONTENT_TYPE),
                response.getContentAsString());
    }

    private Reply postTo(Post op, String skuId, String body, String key, MediaType accept) throws Exception {
        MockHttpServletRequestBuilder request = post(op.path, skuId)
                .accept(accept)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return send(request);
    }

    private Reply postTo(Post op, String skuId, String body, String key) throws Exception {
        return postTo(op, skuId, body, key, MediaType.APPLICATION_JSON);
    }

    private Reply create(String skuId, long quantity, String key) throws Exception {
        return postTo(Post.CREATE, skuId, quantityJson(quantity), key);
    }

    private Reply purchase(String skuId, long quantity, String key) throws Exception {
        return postTo(Post.PURCHASE, skuId, quantityJson(quantity), key);
    }

    private Reply find(String skuId) throws Exception {
        return send(get("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON));
    }

    private static String quantityJson(long quantity) {
        return "{\"quantity\":" + quantity + "}";
    }

    private static String item(String skuId, long quantity) {
        return "{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}";
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void assertItem(Reply reply, String skuId, long quantity) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(reply.contentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
                .as(reply.contentType()).isTrue();
        assertThat(reply.body()).isEqualTo(item(skuId, quantity));
    }

    private static void assertText(Reply reply, int status, String body) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).as(HttpHeaders.CONTENT_TYPE).isNotNull();
        assertThat(MediaType.parseMediaType(reply.contentType()).isCompatibleWith(MediaType.TEXT_PLAIN))
                .as(reply.contentType()).isTrue();
        assertThat(reply.body()).isEqualTo(body);
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private long ledgerRows(String skuId) {
        return count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", skuId);
    }

    private long ledgerRows(String skuId, String reason) {
        return count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = ?", skuId, reason);
    }

    private long allLedgerRows() {
        return count("SELECT count(*) FROM inventory_ledger");
    }

    private long allSkuRows() {
        return count("SELECT count(*) FROM sku");
    }

    private long keyRows() {
        return count("SELECT count(*) FROM idempotency_keys");
    }

    /**
     * A key row as v1 wrote it, before V3: request_hash is SHA-256 of operation, skuId and quantity (Y3), computed in
     * SQL; status, Content-Type and body are null for a claim without a stored response.
     */
    private void storeKeyRow(String key, String operation, String skuId, int quantity, Integer status,
            String contentType, String body) {
        jdbc.sql("""
                INSERT INTO idempotency_keys
                    (idempotency_key, operation, sku_id, request_hash, status, content_type, body)
                VALUES (?::uuid, ?, ?, sha256(convert_to(? || E'\\n' || ? || E'\\n' || ?, 'UTF8')), ?, ?, ?)
                """)
                .params(key, operation, skuId, operation, skuId, Integer.toString(quantity), status, contentType,
                        body)
                .update();
    }

    /** Every column of every key row, request_hash as hex so rows compare by content. */
    private List<Map<String, Object>> keyRowSnapshot() {
        return jdbc.sql("""
                SELECT idempotency_key, operation, sku_id, encode(request_hash, 'hex') AS request_hash,
                       status, content_type, body, created_at
                FROM idempotency_keys ORDER BY idempotency_key
                """).query().listOfRows();
    }

    private void backdate(String key, String interval) {
        int updated = jdbc.sql(
                        "UPDATE idempotency_keys SET created_at = now() - ?::interval WHERE idempotency_key = ?::uuid")
                .params(interval, key)
                .update();
        assertThat(updated).as("key row to backdate").isEqualTo(1);
    }

    // ---- AC1: first request claims, replay is identical, one stock change ----

    @Test
    void keyedAddReplayIsIdenticalAndWritesOneLedgerRow() throws Exception {
        String key = newKey();

        Reply first = create("widget", 5, key);
        Reply replay = create("widget", 5, key);

        assertItem(first, "widget", 5);
        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertThat(keyRows()).isEqualTo(1);
        var row = jdbc.sql("SELECT operation, sku_id, status, content_type, body FROM idempotency_keys "
                + "WHERE idempotency_key = ?::uuid").param(key).query().singleRow();
        assertThat(row.get("operation")).isEqualTo("add");
        assertThat(row.get("sku_id")).isEqualTo("widget");
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
        assertThat(row.get("content_type")).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(row.get("body")).isEqualTo(item("widget", 5));
        assertItem(find("widget"), "widget", 5);
    }

    @Test
    void keyedPurchaseReplayDeductsOnce() throws Exception {
        assertItem(create("widget", 10, null), "widget", 10);
        String key = newKey();

        Reply first = purchase("widget", 3, key);
        Reply replay = purchase("widget", 3, key);

        assertItem(first, "widget", 7);
        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows("widget", "purchase")).isEqualTo(1);
        assertItem(find("widget"), "widget", 7);
    }

    // ---- AC2: same key, different request → 400, nothing written ----

    @Test
    void reuseWithDifferentQuantityReturns400() throws Exception {
        String key = newKey();
        assertItem(create("widget", 5, key), "widget", 5);

        assertText(create("widget", 6, key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertItem(find("widget"), "widget", 5);
    }

    @Test
    void reuseOnDifferentSkuReturns400() throws Exception {
        String key = newKey();
        assertItem(create("widget", 5, key), "widget", 5);

        assertText(create("gadget", 5, key), 400, INVALID_REQUEST);

        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", "gadget")).isZero();
        assertThat(ledgerRows("gadget")).isZero();
        assertThat(ledgerRows("widget")).isEqualTo(1);
    }

    @Test
    void reuseOnOtherPostReturns400AddThenPurchase() throws Exception {
        String key = newKey();
        assertItem(create("widget", 5, key), "widget", 5);

        assertText(purchase("widget", 5, key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget", "purchase")).isZero();
        assertItem(find("widget"), "widget", 5);
    }

    @Test
    void reuseOnOtherPostReturns400PurchaseThenAdd() throws Exception {
        assertItem(create("widget", 10, null), "widget", 10);
        String key = newKey();
        assertItem(purchase("widget", 3, key), "widget", 7);

        assertText(create("widget", 3, key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget", "add")).isEqualTo(1);
        assertItem(find("widget"), "widget", 7);
    }

    // ---- AC3: keys older than 24h are rejected (T1) ----

    @Test
    void keyOlderThan24hReturns400() throws Exception {
        String key = newKey();
        assertItem(create("widget", 5, key), "widget", 5);
        backdate(key, "24 hours 1 second");

        assertText(create("widget", 5, key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertThat(keyRows()).isEqualTo(1);
    }

    @Test
    void keyUnder24hReplays() throws Exception {
        String key = newKey();
        Reply first = create("widget", 5, key);
        assertItem(first, "widget", 5);
        backdate(key, "23 hours 59 minutes");

        assertThat(create("widget", 5, key)).isEqualTo(first);
        assertThat(ledgerRows("widget")).isEqualTo(1);
    }

    // ---- AC5: stored errors replay as stored, even after the state changes (R1, U1) ----

    @Test
    void replayedNotFoundAsStored() throws Exception {
        String key = newKey();
        Reply first = purchase("ghost", 1, key);
        assertText(first, 404, "SKU not found");
        assertItem(create("ghost", 10, null), "ghost", 10);

        Reply replay = purchase("ghost", 1, key);

        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows("ghost", "purchase")).isZero();
        assertItem(find("ghost"), "ghost", 10);
    }

    @Test
    void replayedInsufficientAsStored() throws Exception {
        assertItem(create("widget", 1, null), "widget", 1);
        String key = newKey();
        Reply first = purchase("widget", 5, key);
        assertText(first, 400, "Insufficient inventory");
        assertItem(create("widget", 10, null), "widget", 11);

        Reply replay = purchase("widget", 5, key);

        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows("widget", "purchase")).isZero();
        assertItem(find("widget"), "widget", 11);
    }

    @Test
    void replayedOverflowAsStored() throws Exception {
        Tables.seed(jdbc, "big", Long.MAX_VALUE - 5); // the API can't reach the limit (S11)
        String key = newKey();
        Reply first = create("big", 10, key);
        assertText(first, 400, INVALID_REQUEST);
        assertItem(purchase("big", 100, null), "big", Long.MAX_VALUE - 105);

        Reply replay = create("big", 10, key);

        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows("big", "add")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?::uuid AND status = 400 "
                + "AND body = 'Invalid request'", key)).as("overflow stored against the key (U1)").isEqualTo(1);
    }

    // ---- AC6: malformed keys → 400 before any database work, nothing stored (S3, U3) ----

    /**
     * A present empty key and one non-UUID per POST; the full format matrix is IdempotencyKeyTest's, and the service
     * level is InventoryServiceWriteChecksTest#malformedKeyIsInvalidRequestBeforeAnything.
     */
    static Stream<Arguments> malformedKeyReturns400AndStoresNothing() {
        return Stream.of(Post.values()).flatMap(op -> Stream.of("", "abc").map(key -> Arguments.of(op, key)));
    }

    @ParameterizedTest(name = "{0} key \"{1}\"")
    @MethodSource
    void malformedKeyReturns400AndStoresNothing(Post op, String key) throws Exception {
        assertText(postTo(op, "widget", quantityJson(1), key), 400, INVALID_REQUEST);

        assertThat(keyRows()).isZero();
        assertThat(allLedgerRows()).isZero();
        assertThat(allSkuRows()).isZero();
    }

    // ---- AC7: the hash is of the parsed request (Y3) ----

    @Test
    void whitespaceAndUnknownFieldReplay() throws Exception {
        String key = newKey();
        Reply first = postTo(Post.CREATE, "widget", "{\"quantity\":5}", key);
        assertItem(first, "widget", 5);

        assertThat(postTo(Post.CREATE, "widget", "{ \"quantity\" : 5 , \"extra\": true }", key)).isEqualTo(first);
        assertThat(postTo(Post.CREATE, "widget", "\n{\"extra\":[1,2],\n\"quantity\":5}\n", key)).isEqualTo(first);
        assertText(postTo(Post.CREATE, "widget", "{\"quantity\":6}", key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget")).isEqualTo(1);
    }

    // ---- AC8: replays keep the stored Content-Type (Y4) ----

    @Test
    void replayed200IsJsonAndErrorsAreTextPlain() throws Exception {
        // 200: keyed first == keyed replay == unkeyed success (plan risk: keyed vs unkeyed drift)
        String okKey = newKey();
        Reply ok = create("widget", 5, okKey);
        assertItem(ok, "widget", 5);
        assertThat(create("widget", 5, okKey)).isEqualTo(ok);
        Reply unkeyed = create("gadget", 5, null);
        assertItem(unkeyed, "gadget", 5);
        assertThat(ok.contentType()).isEqualTo(unkeyed.contentType());

        // 404
        String notFoundKey = newKey();
        Reply notFound = purchase("ghost", 1, notFoundKey);
        assertText(notFound, 404, "SKU not found");
        assertThat(purchase("ghost", 1, notFoundKey)).isEqualTo(notFound);
        assertThat(notFound).isEqualTo(purchase("ghost", 1, null));

        // 400 insufficient
        String insufficientKey = newKey();
        Reply insufficient = purchase("widget", 50, insufficientKey);
        assertText(insufficient, 400, "Insufficient inventory");
        assertThat(purchase("widget", 50, insufficientKey)).isEqualTo(insufficient);
        assertThat(insufficient).isEqualTo(purchase("widget", 50, null));

        var contentTypes = jdbc.sql("SELECT status, content_type FROM idempotency_keys ORDER BY status")
                .query().listOfRows();
        assertThat(contentTypes).extracting(r -> r.get("content_type"))
                .containsExactly(MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE,
                        MediaType.TEXT_PLAIN_VALUE);
    }

    /**
     * A33, Y3, Y4, E3: keys stored before V3 keep replaying. The rows are exactly as v1 wrote them (hash, status,
     * Content-Type and body); the same keyed POSTs answer the stored status, Content-Type and body unchanged, write no
     * sku or ledger row, and leave every key row as it was.
     */
    @Test
    void keyStoredBeforeTheMigrationReplaysByteForByte() throws Exception {
        String okKey = newKey();
        String notFoundKey = newKey();
        String insufficientKey = newKey();
        storeKeyRow(okKey, "add", "ABC-1", 5, 200, MediaType.APPLICATION_JSON_VALUE, item("ABC-1", 5));
        storeKeyRow(notFoundKey, "purchase", "NOPE", 1, 404, MediaType.TEXT_PLAIN_VALUE, "SKU not found");
        storeKeyRow(insufficientKey, "purchase", "ABC-1", 10, 400, MediaType.TEXT_PLAIN_VALUE,
                "Insufficient inventory");
        List<Map<String, Object>> before = keyRowSnapshot();

        assertThat(create("ABC-1", 5, okKey))
                .isEqualTo(new Reply(200, MediaType.APPLICATION_JSON_VALUE, item("ABC-1", 5)));
        assertThat(purchase("NOPE", 1, notFoundKey))
                .isEqualTo(new Reply(404, MediaType.TEXT_PLAIN_VALUE, "SKU not found"));
        assertThat(purchase("ABC-1", 10, insufficientKey))
                .isEqualTo(new Reply(400, MediaType.TEXT_PLAIN_VALUE, "Insufficient inventory"));

        assertThat(allLedgerRows()).as("ledger rows").isZero();
        assertThat(allSkuRows()).as("sku rows").isZero();
        assertThat(keyRowSnapshot()).as("key rows").isEqualTo(before);
    }

    /**
     * A18: a committed key row with no stored response, for this very request, is never replayed and never a 500. The
     * keyed POST gets 400 "Invalid request", writes no ledger row, changes no stock and stores nothing.
     */
    @ParameterizedTest
    @EnumSource(Post.class)
    void keyRowWithoutAStoredResponseIsInvalidRequest(Post op) throws Exception {
        assertItem(create("widget", 10, null), "widget", 10);
        String key = newKey();
        storeKeyRow(key, op == Post.CREATE ? "add" : "purchase", "widget", 3, null, null, null);
        List<Map<String, Object>> before = keyRowSnapshot();

        assertText(postTo(op, "widget", quantityJson(3), key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget")).as("ledger rows").isEqualTo(1);
        assertItem(find("widget"), "widget", 10);
        assertThat(keyRowSnapshot()).as("key rows").isEqualTo(before);
    }

    // ---- AC9: Accept that excludes JSON fails before the claim (Y1) ----

    @ParameterizedTest
    @EnumSource(Post.class)
    void keyedAcceptXmlReturns400AndStoresNothing(Post op) throws Exception {
        Tables.seed(jdbc, "widget", 10);
        String key = newKey();

        assertText(postTo(op, "widget", quantityJson(1), key, MediaType.APPLICATION_XML), 400, INVALID_REQUEST);

        assertThat(keyRows()).isZero();
        assertThat(ledgerRows("widget")).isEqualTo(1);

        // the key was not claimed: the same key with Accept: application/json is a first request
        assertItem(postTo(op, "widget", quantityJson(1), key), "widget", op == Post.CREATE ? 11 : 9);
        assertThat(keyRows()).isEqualTo(1);
    }

    /** C3, Y1: an Accept that gives JSON q=0 fails before the claim like one that excludes JSON. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void keyedPostRefusingJsonIsNotStored(Post op) throws Exception {
        Tables.seed(jdbc, "widget", 10);
        String key = newKey();

        assertText(postTo(op, "widget", quantityJson(1), key, MediaType.parseMediaType("application/json;q=0")),
                400, INVALID_REQUEST);

        assertThat(keyRows()).isZero();
        assertThat(ledgerRows("widget")).isEqualTo(1);

        assertItem(postTo(op, "widget", quantityJson(1), key), "widget", op == Post.CREATE ? 11 : 9);
        assertThat(keyRows()).isEqualTo(1);
    }

    // ---- validation failures are not stored (R1, U3) ----

    @ParameterizedTest
    @EnumSource(Post.class)
    void validationFailureNotStoredKeyStaysUsable(Post op) throws Exception {
        Tables.seed(jdbc, "widget", 10);
        String key = newKey();

        assertText(postTo(op, "widget", "{\"quantity\":0}", key), 400, INVALID_REQUEST);
        assertThat(keyRows()).isZero();

        assertItem(postTo(op, "widget", quantityJson(2), key), "widget", op == Post.CREATE ? 12 : 8);
        assertThat(keyRows()).isEqualTo(1);
    }

    @Test
    void invalidSkuIdNotStored() throws Exception {
        String key = newKey();

        assertText(create("-bad", 1, key), 400, INVALID_REQUEST);
        assertText(purchase("-bad", 1, key), 404, "SKU not found");
        assertThat(keyRows()).isZero();
        assertThat(allSkuRows()).isZero();

        assertItem(create("widget", 1, key), "widget", 1);
        assertThat(keyRows()).isEqualTo(1);
    }

    /**
     * AC1, S2, R1, C3: ";" content in the skuId segment is part of the ID, so the keyed request is rejected before the
     * claim (create 400, purchase 404), nothing is stored and ABC-1 is untouched; the key stays usable.
     */
    @Test
    void semicolonSkuIdNotStored() throws Exception {
        Tables.seed(jdbc, "ABC-1", 10);
        String key = newKey();

        assertText(send(post(URI.create("/inventory/ABC-1;lot=7")).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(quantityJson(5)).header(IDEMPOTENCY_KEY, key)),
                400, INVALID_REQUEST);
        assertText(send(post(URI.create("/inventory/ABC-1;x/purchase")).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(quantityJson(2)).header(IDEMPOTENCY_KEY, key)),
                404, "SKU not found");
        assertThat(keyRows()).isZero();
        assertThat(ledgerRows("ABC-1")).isEqualTo(1);
        assertThat(allSkuRows()).isEqualTo(1);

        assertItem(create("widget", 1, key), "widget", 1);
        assertThat(keyRows()).isEqualTo(1);
    }

    private void assertNothingWritten() {
        assertThat(keyRows()).as("idempotency rows").isZero();
        assertThat(allLedgerRows()).as("ledger rows").isZero();
        assertThat(allSkuRows()).as("sku rows").isZero();
    }

    /** U3, A34: the service checks the key before the skuId, so a bad key wins with 400. */
    @Test
    void badKeyWinsOverBadSkuIdOnPurchase() throws Exception {
        assertText(purchase("-bad", 1, "nope"), 400, INVALID_REQUEST);

        assertNothingWritten();
    }

    /** S2, U3, A34: with a valid key, a malformed skuId on purchase is 404 before the claim; nothing is stored. */
    @Test
    void validKeyBadSkuIdOnPurchase404() throws Exception {
        String key = newKey();

        assertText(purchase("-bad", 1, key), 404, "SKU not found");
        assertText(purchase("a".repeat(65), 1, key), 404, "SKU not found");

        assertNothingWritten();
    }

    /** S2, U3, A34: with a valid key, a malformed skuId on create is 400 before the claim; nothing is stored. */
    @Test
    void validKeyBadSkuIdOnCreate400() throws Exception {
        String key = newKey();

        assertText(create("-bad", 1, key), 400, INVALID_REQUEST);
        assertText(create("a".repeat(65), 1, key), 400, INVALID_REQUEST);

        assertNothingWritten();
    }

    /** S3: two Idempotency-Key headers reach the service comma-joined and fail the format check. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void duplicateKeyHeaders400(Post op) throws Exception {
        assertText(send(post(op.path, "widget")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quantityJson(1))
                        .header(IDEMPOTENCY_KEY, newKey(), newKey())),
                400, INVALID_REQUEST);

        assertNothingWritten();
    }

    // ---- G8: without the header nothing changes ----

    @Test
    void unkeyedRequestsNeverTouchIdempotencyTable() throws Exception {
        assertItem(create("widget", 5, null), "widget", 5);
        assertItem(create("widget", 5, null), "widget", 10);
        assertItem(purchase("widget", 3, null), "widget", 7);
        assertText(purchase("widget", 50, null), 400, "Insufficient inventory");
        assertText(purchase("ghost", 1, null), 404, "SKU not found");
        Tables.seed(jdbc, "big", Long.MAX_VALUE);
        assertText(create("big", 1, null), 400, INVALID_REQUEST);

        assertThat(keyRows()).isZero();
    }

    /** S3: the key is a uuid, so an uppercase form of a used key replays it. */
    @Test
    void uppercaseKeyReplaysLowercaseKey() throws Exception {
        String key = newKey();
        Reply first = create("widget", 5, key);
        assertItem(first, "widget", 5);

        assertThat(create("widget", 5, key.toUpperCase())).isEqualTo(first);

        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertThat(keyRows()).isEqualTo(1);
    }

    /**
     * E1, A33, G6: a database error in the write is a plain 500 (nothing retries) and rolls back the claim with the
     * write: nothing is stored and the key stays usable.
     */
    @Test
    void writeFailureStoresNothing() throws Exception {
        fault.failOnInsert("widget", 1, "P0001");
        String key = newKey();

        assertText(create("widget", 5, key), 500, "Internal server error");
        assertThat(fault.attempts()).isEqualTo(1);
        assertThat(keyRows()).isZero();
        assertThat(allLedgerRows()).isZero();
        assertThat(allSkuRows()).as("the sku row rolled back with the claim").isZero();

        assertItem(create("widget", 5, key), "widget", 5);
        assertThat(keyRows()).isEqualTo(1);
        assertThat(ledgerRows("widget")).isEqualTo(1);
    }
}
