package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.inventory.V2Writes.INSUFFICIENT_INVENTORY;
import static com.kgtech.inventoryapi.inventory.V2Writes.INVALID_REQUEST;
import static com.kgtech.inventoryapi.inventory.V2Writes.SKU_NOT_FOUND;
import static com.kgtech.inventoryapi.inventory.V2Writes.itemQuantity;
import static com.kgtech.inventoryapi.inventory.V2Writes.newKey;
import static com.kgtech.inventoryapi.inventory.V2Writes.quantityJson;
import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
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

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.inventory.V2Writes.Op;
import com.kgtech.inventoryapi.inventory.V2Writes.Reply;

/**
 * OD-3, OD-4, OD-6, H2, H6, H9, H10 (F-05, F-10): POST /v2/inventory/{skuId} (add stock) and POST
 * /v2/inventory/{skuId}/purchase against Postgres. Both require an Idempotency-Key; the order is body, key, skuId,
 * claim and write (A34); the answer is a SkuItem; a key replays its stored status, Content-Type and body byte for byte,
 * and is 400 for another operation, skuId or quantity, after 24h, when its response was cleared, or when an
 * unversioned request stored it. The keyed matrix of build v2's unversioned POSTs lives here now (F-07); the
 * unversioned side only rejects the header (UnversionedIdempotencyIntegrationTest). The same chain under concurrency
 * is V2IdempotencyConcurrencyTest's. Not @Transactional; the "recorded" invariant is checked after every test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class V2WritesApiIntegrationTest {

    private static final String DETAILS = "{\"name\":\"Linen shirt\",\"cost\":{\"amount\":12900,\"currency\":\"USD\"}}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private LedgerFaultTrigger fault;

    @BeforeEach
    void clean() {
        fault = new LedgerFaultTrigger(jdbcTemplate);
        fault.drop(); // in case an earlier run was killed before its @AfterEach
        Tables.reset(jdbc);
    }

    @AfterEach
    void dropTheFaultTrigger() {
        fault.drop();
    }

    // ---- helpers ----

    private Reply send(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), response.getHeader(HttpHeaders.CONTENT_TYPE),
                response.getContentAsString());
    }

    /** A well-formed request except for what the caller changes; a null key sends no header. */
    private static MockHttpServletRequestBuilder request(Op op, String skuId, String body, String key) {
        MockHttpServletRequestBuilder request = post(op.template, skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return request;
    }

    private Reply send(Op op, String skuId, long quantity, String key) throws Exception {
        return send(request(op, skuId, quantityJson(quantity), key));
    }

    private Reply send(Op op, String skuId, String body, String key) throws Exception {
        return send(request(op, skuId, body, key));
    }

    private Reply putDetails(String skuId, String details) throws Exception {
        return send(put("/v2/inventory/{skuId}/details", skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(details));
    }

    private static void assertText(Reply reply, int status, String body) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
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

    private long quantity(String skuId) {
        return count("SELECT quantity FROM sku WHERE sku_id = ?", skuId);
    }

    private long keyRows() {
        return count("SELECT count(*) FROM idempotency_keys");
    }

    private void backdate(String key, String interval) {
        assertThat(jdbc.sql("UPDATE idempotency_keys SET created_at = now() - ?::interval "
                + "WHERE idempotency_key = ?::uuid").params(interval, key).update()).as("key row").isEqualTo(1);
    }

    // ---- success ----

    @Test
    void addAnswers200WithTheItemAndKeepsAStoredRow() throws Exception {
        String key = newKey();

        Reply first = send(Op.ADD, "A-1", 5, key);
        Reply second = send(Op.ADD, "A-1", 2, newKey());

        assertThat(itemQuantity(first, "A-1")).isEqualTo(5);
        assertThat(itemQuantity(second, "A-1")).isEqualTo(7);
        assertThat(quantity("A-1")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'add'", "A-1"))
                .isEqualTo(2);
        assertThat(keyRows()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?::uuid AND operation = 'add' "
                + "AND sku_id = 'A-1' AND status = 200 AND content_type LIKE 'application/json%' AND body = ?",
                key, first.body())).isEqualTo(1);
        assertThat(storedHash(key)).as("H10: /v2 stores the versioned hash").isEqualTo(V2Writes.v2Hash(Op.ADD, "A-1", 5));
    }

    @Test
    void purchaseAnswers200WithTheRemainingItem() throws Exception {
        Tables.seed(jdbc, "P-1", 10);

        Reply reply = send(Op.PURCHASE, "P-1", 3, newKey());

        assertThat(itemQuantity(reply, "P-1")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase' "
                + "AND quantity_delta = -3", "P-1")).isEqualTo(1);
        assertThat(quantity("P-1")).isEqualTo(7);
    }

    /** H6: the item carries the details when the SKU has them and no details property when it has none. */
    @ParameterizedTest
    @EnumSource(Op.class)
    void theItemCarriesTheDetailsOnlyWhenTheSkuHasThem(Op op) throws Exception {
        Tables.seed(jdbc, "D-1", 10);
        Reply without = send(op, "D-1", 1, newKey());
        assertThat(without.status()).as(without.body()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(without.body(), "$")).doesNotContainKey("details");
        assertThat(putDetails("D-1", DETAILS).status()).isEqualTo(200);

        Reply with = send(op, "D-1", 1, newKey());

        assertThat(with.status()).as(with.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(with.body(), "$.details.name")).isEqualTo("Linen shirt");
        assertThat(JsonPath.<Integer>read(with.body(), "$.details.cost.amount")).isEqualTo(12900);
        assertThat(JsonPath.<Integer>read(with.body(), "$.quantity")).isEqualTo(op == Op.ADD ? 12 : 8);
        assertThat(JsonPath.<String>read(with.body(), "$.skuId")).isEqualTo("D-1");
    }

    // ---- error outcomes: which are stored (R1) and which are not ----

    /** op, skuId, stock seeded first (null: no such SKU), request quantity, status, text, idempotency rows stored. */
    static Stream<Arguments> errorOutcomes() {
        return Stream.of(
                Arguments.of("insufficient", Op.PURCHASE, "widget", 2L, 3L, 400, INSUFFICIENT_INVENTORY, 1),
                Arguments.of("missing sku", Op.PURCHASE, "ghost", null, 1L, 404, SKU_NOT_FOUND, 1),
                Arguments.of("overflow", Op.ADD, "big", Long.MAX_VALUE - 5, 10L, 400, INVALID_REQUEST, 1),
                // validation 400s and 404s for a malformed skuId are never stored
                Arguments.of("purchase, skuId fails G11", Op.PURCHASE, "-a", null, 1L, 404, SKU_NOT_FOUND, 0),
                Arguments.of("purchase, skuId with a space", Op.PURCHASE, "bad id", null, 1L, 404, SKU_NOT_FOUND, 0),
                Arguments.of("add, skuId fails G11", Op.ADD, "-a", null, 1L, 400, INVALID_REQUEST, 0),
                Arguments.of("add, skuId with a space", Op.ADD, "bad id", null, 1L, 400, INVALID_REQUEST, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorOutcomes")
    void errorsAreTextPlainAndOnlyBusinessOutcomesAreStored(String what, Op op, String skuId, Long seeded,
            long quantity, int status, String text, int storedRows) throws Exception {
        if (seeded != null) {
            Tables.seed(jdbc, skuId, seeded);
        }
        Map<String, Long> before = Tables.counts(jdbc);

        Reply reply = send(op, skuId, quantity, newKey());

        assertText(reply, status, text);
        Map<String, Long> after = Tables.counts(jdbc);
        assertThat(after.get("idempotency_keys")).as("stored rows").isEqualTo((long) storedRows);
        assertThat(after.get("inventory_ledger")).isEqualTo(before.get("inventory_ledger"));
        assertThat(after.get("sku")).isEqualTo(before.get("sku"));
        if (seeded != null) {
            assertThat(quantity(skuId)).isEqualTo(seeded);
        }
    }

    // ---- the Idempotency-Key is required and checked before the skuId (H2, A34) ----

    /** Absent, empty, not a UUID, a UUID one character short, a UUID with a stray suffix. */
    static Stream<Arguments> badKeys() {
        return Stream.of(Op.values()).flatMap(op -> Stream.of(null, "", "abc", "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6",
                "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b0").map(key -> Arguments.of(op, key)));
    }

    @ParameterizedTest(name = "{0} key [{1}]")
    @MethodSource("badKeys")
    void aMissingOrMalformedKeyIs400AndNothingIsWritten(Op op, String key) throws Exception {
        Tables.seed(jdbc, "widget", 5);
        Map<String, Long> before = Tables.counts(jdbc);

        assertText(send(op, "widget", 1, key), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
        assertThat(quantity("widget")).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(Op.class)
    void twoKeyHeadersAre400(Op op) throws Exception {
        Tables.seed(jdbc, "widget", 5);

        assertText(send(request(op, "widget", quantityJson(1), null).header(IDEMPOTENCY_KEY, newKey(), newKey())),
                400, INVALID_REQUEST);

        assertThat(keyRows()).isZero();
        assertThat(quantity("widget")).isEqualTo(5);
    }

    /** U3, A34: body first, then the key, then the skuId; none of these 400s or 404s is stored. */
    @Test
    void theOrderIsBodyThenKeyThenSkuId() throws Exception {
        // key before skuId: a bad key on a bad skuId is 400 even on purchase (whose bad skuId alone is 404)
        assertText(send(Op.PURCHASE, "-a", 1, null), 400, INVALID_REQUEST);
        assertText(send(Op.PURCHASE, "-a", 1, "abc"), 400, INVALID_REQUEST);
        // a good key and body on a bad skuId: 404 on purchase, 400 on add
        assertText(send(Op.PURCHASE, "-a", 1, newKey()), 404, SKU_NOT_FOUND);
        assertText(send(Op.ADD, "-a", 1, newKey()), 400, INVALID_REQUEST);
        // body before skuId: a bad body on a bad skuId is 400 even on purchase
        assertText(send(Op.PURCHASE, "-a", "{\"quantity\":0}", newKey()), 400, INVALID_REQUEST);
        assertText(send(Op.PURCHASE, "-a", "{\"quantity\":0}", null), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc).values()).containsOnly(0L);
    }

    // ---- validation 400s are not stored and leave the key usable ----

    static Stream<Arguments> invalidBodies() {
        return Stream.of(Op.values()).flatMap(op -> Stream.of("{\"quantity\":0}", "{\"quantity\":-1}",
                "{\"quantity\":2147483648}", "{\"quantity\":\"5\"}", "{\"quantity\":1.5}", "{\"quantity\":null}", "{}",
                "[]", "{\"quantity\":", "", "{\"quantity\":1,\"quantity\":2}" /* M-11 */)
                .map(body -> Arguments.of(op, body)));
    }

    @ParameterizedTest(name = "{0} body [{1}]")
    @MethodSource("invalidBodies")
    void anInvalidBodyIs400NotStoredAndTheKeyStaysUsable(Op op, String body) throws Exception {
        Tables.seed(jdbc, "widget", 10);
        Map<String, Long> before = Tables.counts(jdbc);
        String key = newKey();

        assertText(send(op, "widget", body, key), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
        assertThat(itemQuantity(send(op, "widget", 1, key), "widget")).isEqualTo(op == Op.ADD ? 11 : 9);
    }

    /** OD-6: the create body of build v2 is a bad quantity body now (quantity missing). */
    @Test
    void theOldCreateBodyIs400AndCreatesNothing() throws Exception {
        String create = "{\"details\":{\"name\":\"n\"},\"initialQuantity\":1}";

        assertText(send(Op.ADD, "NEW-1", create, newKey()), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc).values()).containsOnly(0L);
    }

    /** OD-6: PUT on the item path is gone; the answer is Spring's 405 with the methods that remain. */
    @Test
    void putOnTheItemPathIs405() throws Exception {
        Tables.seed(jdbc, "widget", 5);

        MockHttpServletResponse response = mvc.perform(put("/v2/inventory/{skuId}", "widget").accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content("{\"name\":\"n\"}").header(IDEMPOTENCY_KEY, newKey()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
        assertThat(response.getContentAsString()).isEqualTo("Method Not Allowed");
        assertThat(response.getHeader(HttpHeaders.ALLOW)).contains("GET", "POST").doesNotContain("PUT");
        assertThat(count("SELECT count(*) FROM sku_details")).isZero();
        assertThat(keyRows()).isZero();
    }

    // ---- Accept and Content-Type: 400, never 406 or 415 ----

    @ParameterizedTest
    @EnumSource(Op.class)
    void anAcceptThatExcludesJsonIs400AndWritesNothing(Op op) throws Exception {
        Tables.seed(jdbc, "widget", 5);
        Map<String, Long> before = Tables.counts(jdbc);

        for (String accept : List.of("application/xml", "application/json;q=0", "text/html")) {
            assertText(send(post(op.template, "widget").contentType(APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, accept).content(quantityJson(1)).header(IDEMPOTENCY_KEY, newKey())),
                    400, INVALID_REQUEST);
        }

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Op.class)
    void aWrongOrMissingContentTypeIs400NotFor415(Op op) throws Exception {
        Tables.seed(jdbc, "widget", 5);
        Map<String, Long> before = Tables.counts(jdbc);

        assertText(send(post(op.template, "widget").accept(APPLICATION_JSON).contentType(MediaType.TEXT_PLAIN)
                .content(quantityJson(1)).header(IDEMPOTENCY_KEY, newKey())), 400, INVALID_REQUEST);
        assertText(send(post(op.template, "widget").accept(APPLICATION_JSON).content(quantityJson(1))
                .header(IDEMPOTENCY_KEY, newKey())), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
    }

    // ---- replay: the stored status, Content-Type and body, byte for byte (Y4, R1) ----

    @Test
    void anAddReplaysByteForByteAndWritesOnce() throws Exception {
        String key = newKey();
        Reply first = send(Op.ADD, "R-1", 5, key);

        assertThat(itemQuantity(first, "R-1")).isEqualTo(5);
        assertThat(send(Op.ADD, "R-1", 5, key)).isEqualTo(first);
        // Y3: the hash comes from the parsed request, so whitespace and unknown fields still replay
        assertThat(send(Op.ADD, "R-1", "{ \"quantity\" : 5 , \"extra\": [1,2] }", key)).isEqualTo(first);
        assertThat(send(Op.ADD, "R-1", "\n{\"extra\":true,\n\"quantity\":5}\n", key)).isEqualTo(first);
        assertThat(ledgerRows("R-1")).isEqualTo(1);
        assertThat(quantity("R-1")).isEqualTo(5);
        assertThat(keyRows()).isEqualTo(1);
    }

    @Test
    void aPurchaseReplaysTheStoredRemainderNotTheCurrentStock() throws Exception {
        Tables.seed(jdbc, "R-2", 10);
        String key = newKey();
        Reply first = send(Op.PURCHASE, "R-2", 3, key);
        Tables.seed(jdbc, "R-2", 100);

        assertThat(itemQuantity(first, "R-2")).isEqualTo(7);
        assertThat(send(Op.PURCHASE, "R-2", 3, key)).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", "R-2"))
                .isEqualTo(1);
        assertThat(quantity("R-2")).isEqualTo(107);
    }

    /** R1: a 404 and a 400 "Insufficient inventory" are stored like a 200, and replay after the state changes. */
    @Test
    void storedErrorsReplayAsStoredEvenAfterTheStateChanges() throws Exception {
        String missing = newKey();
        Reply notFound = send(Op.PURCHASE, "ghost", 1, missing);
        assertText(notFound, 404, SKU_NOT_FOUND);
        Tables.seed(jdbc, "ghost", 10);
        String short_ = newKey();
        Tables.seed(jdbc, "short", 2);
        Reply insufficient = send(Op.PURCHASE, "short", 5, short_);
        assertText(insufficient, 400, INSUFFICIENT_INVENTORY);
        Tables.seed(jdbc, "short", 100);

        assertThat(send(Op.PURCHASE, "ghost", 1, missing)).isEqualTo(notFound);
        assertThat(send(Op.PURCHASE, "short", 5, short_)).isEqualTo(insufficient);

        assertThat(quantity("ghost")).isEqualTo(10);
        assertThat(quantity("short")).isEqualTo(102);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE reason = 'purchase'")).isZero();
    }

    // ---- reuse, expiry and cleared rows are 400 (S8, T1, A18) ----

    @Test
    void aKeyReusedForAnotherQuantitySkuOrOperationIs400() throws Exception {
        Tables.seed(jdbc, "other", 10);
        String addKey = newKey();
        assertThat(itemQuantity(send(Op.ADD, "widget", 5, addKey), "widget")).isEqualTo(5);
        String purchaseKey = newKey();
        assertThat(itemQuantity(send(Op.PURCHASE, "widget", 2, purchaseKey), "widget")).isEqualTo(3);
        Map<String, Long> before = Tables.counts(jdbc);

        assertText(send(Op.ADD, "widget", 6, addKey), 400, INVALID_REQUEST);
        assertText(send(Op.ADD, "other", 5, addKey), 400, INVALID_REQUEST);
        assertText(send(Op.PURCHASE, "widget", 5, addKey), 400, INVALID_REQUEST);
        assertText(send(Op.PURCHASE, "widget", 1, purchaseKey), 400, INVALID_REQUEST);
        assertText(send(Op.PURCHASE, "other", 2, purchaseKey), 400, INVALID_REQUEST);
        assertText(send(Op.ADD, "widget", 2, purchaseKey), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
        assertThat(quantity("widget")).isEqualTo(3);
        assertThat(quantity("other")).isEqualTo(10);
    }

    @Test
    void aKeyOlderThan24HoursIs400AndOneUnderReplays() throws Exception {
        String old = newKey();
        String recent = newKey();
        send(Op.ADD, "widget", 5, old);
        Reply first = send(Op.ADD, "widget", 5, recent);
        backdate(old, "24 hours 1 second");
        backdate(recent, "23 hours 59 minutes");

        assertText(send(Op.ADD, "widget", 5, old), 400, INVALID_REQUEST);
        assertThat(send(Op.ADD, "widget", 5, recent)).isEqualTo(first);

        assertThat(ledgerRows("widget")).isEqualTo(2);
        assertThat(keyRows()).isEqualTo(2);
    }

    /** A18: a committed row whose response was cleared by the README's clean-up is used up, whatever its age. */
    @ParameterizedTest
    @EnumSource(Op.class)
    void aClearedRowIs400(Op op) throws Exception {
        Tables.seed(jdbc, "widget", 5);
        String key = newKey();
        assertThat(send(op, "widget", 1, key).status()).isEqualTo(200);
        jdbc.sql("UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL "
                + "WHERE idempotency_key = ?::uuid").param(key).update();

        assertText(send(op, "widget", 1, key), 400, INVALID_REQUEST);

        assertThat(ledgerRows("widget")).isEqualTo(2);
    }

    // ---- H10: the API version is part of the request hash ----

    @ParameterizedTest
    @EnumSource(Op.class)
    void aKeyStoredByAnUnversionedRequestNeverReplaysOnV2(Op op) throws Exception {
        Tables.seed(jdbc, "widget", 10);
        String key = newKey();
        // A row as build v2's unversioned POST stored it (the unversioned POSTs reject keys now, but old rows stay).
        jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash, status, "
                + "content_type, body) VALUES (?::uuid, ?, 'widget', ?, 200, 'application/json', "
                + "'{\"skuId\":\"widget\",\"quantity\":11}')")
                .params(key, op.dbValue, V2Writes.unversionedHash(op, "widget", 1)).update();
        Map<String, Long> before = Tables.counts(jdbc);

        assertText(send(op, "widget", 1, key), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
        assertThat(quantity("widget")).isEqualTo(10);
    }

    @Test
    void thePurchaseStoresTheVersionedHash() throws Exception {
        Tables.seed(jdbc, "widget", 5);
        String key = newKey();

        assertThat(send(Op.PURCHASE, "widget", 2, key).status()).isEqualTo(200);

        assertThat(storedHash(key)).isEqualTo(V2Writes.v2Hash(Op.PURCHASE, "widget", 2));
    }

    // ---- ported from build v2's unversioned keyed matrix (F-07) ----

    /** R1, U1: an overflow 400 is stored against the key and replays after the state changes. */
    @Test
    void anOverflowIsStoredAndReplaysAsStored() throws Exception {
        Tables.seed(jdbc, "big", Long.MAX_VALUE - 5); // the API can't reach the limit (S11)
        String key = newKey();
        Reply first = send(Op.ADD, "big", 10, key);
        assertText(first, 400, INVALID_REQUEST);
        assertThat(itemQuantity(send(Op.PURCHASE, "big", 100, newKey()), "big")).isEqualTo(Long.MAX_VALUE - 105);

        assertThat(send(Op.ADD, "big", 10, key)).isEqualTo(first);

        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = 'big' AND reason = 'add'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?::uuid AND status = 400 "
                + "AND content_type LIKE 'text/plain%' AND body = 'Invalid request'", key)).isEqualTo(1);
    }

    /** S3: the key is a uuid, so an upper-case form of a used key replays it. */
    @Test
    void anUpperCaseKeyReplaysTheLowerCaseKey() throws Exception {
        String key = newKey();
        Reply first = send(Op.ADD, "widget", 5, key);
        assertThat(itemQuantity(first, "widget")).isEqualTo(5);

        assertThat(send(Op.ADD, "widget", 5, key.toUpperCase())).isEqualTo(first);

        assertThat(ledgerRows("widget")).isEqualTo(1);
        assertThat(keyRows()).isEqualTo(1);
    }

    /** A 500 rolls back the claim: nothing is stored and the key stays usable. */
    @Test
    void aWriteFailureStoresNothingAndTheKeyStaysUsable() throws Exception {
        fault.failOnInsert("widget", 1, "P0001");
        String key = newKey();

        assertText(send(Op.ADD, "widget", 5, key), 500, "Internal server error");
        assertThat(fault.attempts()).isEqualTo(1);
        assertThat(Tables.counts(jdbc).values()).containsOnly(0L);

        assertThat(itemQuantity(send(Op.ADD, "widget", 5, key), "widget")).isEqualTo(5);
        assertThat(keyRows()).isEqualTo(1);
        assertThat(ledgerRows("widget")).isEqualTo(1);
    }

    private byte[] storedHash(String key) {
        return jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE idempotency_key = ?::uuid").param(key)
                .query((rs, n) -> rs.getBytes(1)).single();
    }
}
