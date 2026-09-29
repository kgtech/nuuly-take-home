package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * OD-4, H3, L31, invariant 5 (F-07): the unversioned API has no Idempotency-Key. A POST that carries the header, with
 * any value, is 400 "Invalid request" and writes and stores nothing; no unversioned request reads or writes the
 * idempotency table. The keyed behaviour they lost is V2WritesApiIntegrationTest's. Not @Transactional.
 */
@IntegrationTest
@AutoConfigureMockMvc
class UnversionedIdempotencyIntegrationTest {

    private static final String INVALID_REQUEST = "Invalid request";
    private static final String QUANTITY_1 = "{\"quantity\":1}";

    /** The two unversioned POST operations. */
    enum Op {
        ADD("/inventory/{skuId}"),
        PURCHASE("/inventory/{skuId}/purchase");

        final String template;

        Op(String template) {
            this.template = template;
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "widget", 5);
    }

    @AfterEach
    void balancesMatchTheLedger() {
        assertThat(Invariants.balanceMismatches(jdbc)).as("sku.quantity equals the ledger SUM").isEmpty();
    }

    // ---- helpers ----

    private static MockHttpServletRequestBuilder request(Op op, String skuId, String body, String... keyLines) {
        MockHttpServletRequestBuilder request = post(op.template, skuId).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).content(body);
        if (keyLines.length > 0) {
            request.header(IDEMPOTENCY_KEY, (Object[]) keyLines);
        }
        return request;
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static void assertText(MockHttpServletResponse response, int status, String body) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_PLAIN))
                .as(response.getContentType()).isTrue();
        assertThat(response.getContentAsString()).isEqualTo(body);
    }

    private long quantity(String skuId) {
        return jdbc.sql("SELECT quantity FROM sku WHERE sku_id = ?").param(skuId).query(Long.class).single();
    }

    // ---- H3: any Idempotency-Key on an unversioned POST is 400 and changes nothing ----

    /** A valid UUID, an empty value, a non-UUID and two header lines, per POST. */
    static Stream<Arguments> presentKeys() {
        String uuid = UUID.randomUUID().toString();
        return Stream.of(Op.values()).flatMap(op -> Stream.of(
                Arguments.of(op, "valid", new String[] {uuid}),
                Arguments.of(op, "empty", new String[] {""}),
                Arguments.of(op, "malformed", new String[] {"abc"}),
                Arguments.of(op, "two lines", new String[] {uuid, UUID.randomUUID().toString()})));
    }

    @ParameterizedTest(name = "{0} key: {1}")
    @MethodSource("presentKeys")
    void aPresentKeyIs400AndNothingIsWrittenOrStored(Op op, String what, String[] keyLines) throws Exception {
        Map<String, Long> before = Tables.counts(jdbc);

        assertText(send(request(op, "widget", QUANTITY_1, keyLines)), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
        assertThat(Tables.counts(jdbc).get("idempotency_keys")).isZero();
        assertThat(quantity("widget")).isEqualTo(5);
    }

    /** Order (H3): the key is checked before the skuId, so purchase's malformed-skuId 404 becomes 400 with a key. */
    @Test
    void aPresentKeyBeatsAMalformedSkuId() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Long> before = Tables.counts(jdbc);

        assertText(send(request(Op.PURCHASE, "-bad", QUANTITY_1, key)), 400, INVALID_REQUEST);
        assertText(send(request(Op.ADD, "-bad", QUANTITY_1, key)), 400, INVALID_REQUEST);
        // without the header the frozen answers stay: 404 on purchase, 400 on add (G11)
        assertText(send(request(Op.PURCHASE, "-bad", QUANTITY_1)), 404, "SKU not found");
        assertText(send(request(Op.ADD, "-bad", QUANTITY_1)), 400, INVALID_REQUEST);

        assertThat(Tables.counts(jdbc)).isEqualTo(before);
    }

    /** L31: the reads carry no key rule; a key on a GET is ignored. */
    @Test
    void aKeyOnAGetIsIgnored() throws Exception {
        String key = UUID.randomUUID().toString();

        assertThat(send(get("/inventory").accept(APPLICATION_JSON).header(IDEMPOTENCY_KEY, key)).getStatus())
                .isEqualTo(200);
        assertThat(send(get("/inventory/{skuId}", "widget").accept(APPLICATION_JSON).header(IDEMPOTENCY_KEY, ""))
                .getStatus()).isEqualTo(200);

        assertThat(Tables.counts(jdbc).get("idempotency_keys")).isZero();
    }

    // ---- invariant 5: no unversioned request reads or writes the idempotency table ----

    /** One success and one failure per unversioned operation; the failures include the ones a key used to store. */
    private record Case(String name, MockHttpServletRequestBuilder success, long ledgerRowsAdded,
            MockHttpServletRequestBuilder failure, int failureStatus, String failureBody) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> operations() {
        return Stream.of(
                new Case("GET /inventory", get("/inventory").accept(APPLICATION_JSON), 0,
                        get("/inventory?after=a&after=b").accept(APPLICATION_JSON), 400, INVALID_REQUEST),
                new Case("GET /inventory/{skuId}", get("/inventory/widget").accept(APPLICATION_JSON), 0,
                        get("/inventory/ghost").accept(APPLICATION_JSON), 404, "SKU not found"),
                new Case("POST /inventory/{skuId}", request(Op.ADD, "widget", "{\"quantity\":5}"), 1,
                        request(Op.ADD, "big", "{\"quantity\":5}"), 400, INVALID_REQUEST), // overflow
                new Case("POST /inventory/{skuId}/purchase", request(Op.PURCHASE, "widget", "{\"quantity\":3}"), 1,
                        request(Op.PURCHASE, "widget", "{\"quantity\":1000}"), 400, "Insufficient inventory"));
    }

    /**
     * The table is renamed away while the requests run, so any statement that reads or writes it fails and would
     * answer 500; afterwards it is back and holds no row (invariant 5).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    void anUnversionedRequestNeverTouchesTheIdempotencyTable(Case operation) throws Exception {
        Tables.seed(jdbc, "big", Long.MAX_VALUE);
        Map<String, Long> before = Tables.counts(jdbc);
        MockHttpServletResponse ok;
        MockHttpServletResponse failed;

        jdbc.sql("ALTER TABLE idempotency_keys RENAME TO idempotency_keys_hidden").update();
        try {
            ok = send(operation.success());
            failed = send(operation.failure());
        } finally {
            jdbc.sql("ALTER TABLE idempotency_keys_hidden RENAME TO idempotency_keys").update();
        }

        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertText(failed, operation.failureStatus(), operation.failureBody());
        Map<String, Long> after = Tables.counts(jdbc);
        assertThat(after.get("idempotency_keys")).isZero();
        assertThat(after.get("sku")).isEqualTo(before.get("sku"));
        assertThat(after.get("inventory_ledger")).isEqualTo(before.get("inventory_ledger") + operation.ledgerRowsAdded());
        assertThat(quantity("big")).isEqualTo(Long.MAX_VALUE);
    }
}
