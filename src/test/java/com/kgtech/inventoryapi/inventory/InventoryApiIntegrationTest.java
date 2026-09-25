package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * The four spec operations end to end against Postgres (AC4, AC6, AC7, G12, Y1, NQ6). Not @Transactional: every
 * request commits its own transaction, so the tables are emptied before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InventoryApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        // test-only deletes; the application never deletes ledger or sku rows (G5)
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
    }

    private ResultActions create(String skuId, long quantity) throws Exception {
        return mvc.perform(post("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"));
    }

    private ResultActions purchase(String skuId, long quantity) throws Exception {
        return mvc.perform(post("/inventory/{skuId}/purchase", skuId).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"));
    }

    private ResultActions find(String skuId) throws Exception {
        return mvc.perform(get("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions list() throws Exception {
        return mvc.perform(get("/inventory").accept(MediaType.APPLICATION_JSON));
    }

    private static String item(String skuId, long quantity) {
        return "{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}";
    }

    private static void expectJson(ResultActions result, String json) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(json, JsonCompareMode.STRICT));
    }

    private static void expectText(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }

    private void seedLedger(String skuId, long delta) {
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?) ON CONFLICT DO NOTHING").param(skuId).update();
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, 'add')")
                .params(skuId, delta)
                .update();
    }

    private long ledgerRows(String skuId) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?")
                .param(skuId).query(Long.class).single();
    }

    private long skuRows(String skuId) {
        return jdbc.sql("SELECT count(*) FROM sku WHERE sku_id = ?").param(skuId).query(Long.class).single();
    }

    private long allLedgerRows() {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single();
    }

    private long allSkuRows() {
        return jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single();
    }

    @Test
    void listIsEmptyArrayWhenNoSkus() throws Exception {
        expectJson(list(), "[]");
    }

    @Test
    void specFlowEndToEnd() throws Exception {
        expectJson(create("widget", 10), item("widget", 10));
        expectJson(create("widget", 5), item("widget", 15));
        expectJson(find("widget"), item("widget", 15));
        expectText(purchase("widget", 20), 400, "Insufficient inventory");
        expectText(purchase("missing", 1), 404, "SKU not found");
        expectText(find("missing"), 404, "SKU not found");
        expectJson(find("widget"), item("widget", 15));
    }

    /** AC6, G5: a SKU at 0 keeps its row and stays listed. */
    @Test
    void skuSoldToZeroStaysListed() throws Exception {
        expectJson(create("widget", 3), item("widget", 3));
        expectJson(purchase("widget", 3), item("widget", 0));
        expectJson(find("widget"), item("widget", 0));
        expectJson(list(), "[" + item("widget", 0) + "]");
    }

    /** AC6, G1, G11: skuIds are case-sensitive and listed in COLLATE "C" order. */
    @Test
    void skuIdsAreCaseSensitive() throws Exception {
        expectJson(create("ABC", 5), item("ABC", 5));
        expectJson(create("abc", 7), item("abc", 7));

        expectText(find("Abc"), 404, "SKU not found");
        expectJson(list(), "[" + item("ABC", 5) + "," + item("abc", 7) + "]");

        expectJson(purchase("ABC", 5), item("ABC", 0));
        expectJson(find("abc"), item("abc", 7));
    }

    /** AC7, G2: a balance above Integer.MAX_VALUE is returned as a plain JSON integer. */
    @Test
    void balanceAboveIntMaxIsReturned() throws Exception {
        String expected = "{\"skuId\":\"big\",\"quantity\":4294967294}";

        create("big", Integer.MAX_VALUE).andExpect(status().isOk());
        create("big", Integer.MAX_VALUE)
                .andExpect(status().isOk())
                .andExpect(content().string(expected));
        find("big").andExpect(status().isOk()).andExpect(content().string(expected));
        list().andExpect(status().isOk()).andExpect(content().string("[" + expected + "]"));
    }

    /** G12, U1: an add past Long.MAX_VALUE is 400 "Invalid request" and inserts nothing; the exact limit is fine. */
    @Test
    void overflowReturns400AndWritesNothing() throws Exception {
        seedLedger("big", Long.MAX_VALUE - 1); // the API can't reach the limit

        expectText(create("big", 2), 400, "Invalid request");
        assertThat(ledgerRows("big")).isEqualTo(1);

        create("big", 1)
                .andExpect(status().isOk())
                .andExpect(content().string("{\"skuId\":\"big\",\"quantity\":9223372036854775807}"));
        assertThat(ledgerRows("big")).isEqualTo(2);
    }

    /** AC4, Y1: a POST whose Accept excludes JSON fails before any write. */
    @Test
    void postWithXmlAcceptWritesNoRows() throws Exception {
        expectText(mvc.perform(post("/inventory/{skuId}", "new").accept(MediaType.APPLICATION_XML)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}")), 400, "Invalid request");
        assertThat(skuRows("new")).isZero();
        assertThat(ledgerRows("new")).isZero();

        seedLedger("stocked", 10);
        expectText(mvc.perform(post("/inventory/{skuId}/purchase", "stocked").accept(MediaType.APPLICATION_XML)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}")), 400, "Invalid request");
        assertThat(ledgerRows("stocked")).isEqualTo(1);
        expectJson(find("stocked"), item("stocked", 10));
    }

    /** AC4, U2: GET ignores the Accept header. */
    @Test
    void getWithXmlAcceptReturnsJson() throws Exception {
        seedLedger("widget", 4);

        expectJson(mvc.perform(get("/inventory/{skuId}", "widget").accept(MediaType.APPLICATION_XML)),
                item("widget", 4));
        expectJson(mvc.perform(get("/inventory").accept(MediaType.APPLICATION_XML)), "[" + item("widget", 4) + "]");
    }

    /** G4, U3: body validation wins over the missing SKU. */
    @Test
    void invalidBodyOnMissingSkuReturns400() throws Exception {
        expectText(purchase("missing", 0), 400, "Invalid request");
        assertThat(allLedgerRows()).isZero();
    }

    static Stream<Arguments> strictJacksonAppliesWithSpringdocLoaded() {
        return Stream.of(
                Arguments.of("/inventory/{skuId}", "{\"quantity\":\"10\"}"),
                Arguments.of("/inventory/{skuId}", "{\"quantity\":10.5}"),
                Arguments.of("/inventory/{skuId}/purchase", "{\"quantity\":\"10\"}"),
                Arguments.of("/inventory/{skuId}/purchase", "{\"quantity\":10.5}"));
    }

    /** NQ6, G13: the strict Jackson 3 settings hold in the full context, with swagger-core's Jackson 2 present. */
    @ParameterizedTest(name = "POST {0} {1}")
    @MethodSource
    void strictJacksonAppliesWithSpringdocLoaded(String template, String body) throws Exception {
        expectText(mvc.perform(post(template, "widget").accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "Invalid request");
        assertThat(allLedgerRows()).isZero();
        assertThat(allSkuRows()).isZero();
    }
}
