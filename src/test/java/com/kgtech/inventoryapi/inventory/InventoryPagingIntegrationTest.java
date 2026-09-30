package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kgtech.inventoryapi.Tables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.IntegrationTest;

/**
 * G9, C2, H4 against Postgres (S11): the unversioned GET /inventory. Keyset pages over the sku table in COLLATE "C"
 * order, balances from the ledger SUM, a fixed page of 250 and a Link that carries only after; limit (in any form) and
 * every other parameter are ignored. The limit-based paging is V2ListPagingIntegrationTest's. Not @Transactional:
 * every request commits its own transaction, so the tables are emptied before each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class InventoryPagingIntegrationTest {

    private static final Pattern LINK_VALUE = Pattern.compile("^<([^>]+)>; rel=\"next\"$");
    /** Seeded by {@link #seedMixed()}, in COLLATE "C" order: upper case before lower case, '-' before '.'. */
    private static final List<String> MIXED = List.of("A-1", "B-2", "C-3", "Z-9", "a-1", "b-2", "c.3");
    private static final int MAX_PAGES = 300;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        // test-only deletes; the application never deletes ledger or sku rows (G5)
        Tables.reset(jdbc);
    }

    private void create(String skuId, int quantity) throws Exception {
        mvc.perform(post("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    private void purchase(String skuId, int quantity) throws Exception {
        mvc.perform(post("/inventory/{skuId}/purchase", skuId).accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    /** Seeds {@link #MIXED}, inserted out of order, including a SKU at 0 (G5) and one at Long.MAX_VALUE. */
    private void seedMixed() throws Exception {
        create("c.3", 1);
        create("a-1", 4);
        purchase("a-1", 4); // 0 stock, still listed
        create("Z-9", 1);
        Tables.seed(jdbc, "Z-9", Long.MAX_VALUE - 1); // the API can't reach the limit
        create("b-2", 5);
        create("C-3", 5);
        purchase("C-3", 2);
        create("B-2", 5);
        create("B-2", 7);
        create("A-1", 5);
    }

    private static URI uri(String after) {
        return UriComponentsBuilder.fromPath("/inventory").queryParam("after", "{after}").encode()
                .buildAndExpand(Map.of("after", after)).toUri();
    }

    private ResultActions list(URI uri) throws Exception {
        return mvc.perform(get(uri).accept(MediaType.APPLICATION_JSON));
    }

    private String defaultPageBody() throws Exception {
        return list(URI.create("/inventory"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(LINK))
                .andReturn().getResponse().getContentAsString();
    }

    /** Seeds count SKUs p001, p002, … with no ledger rows (quantity 0); ids sort numerically in COLLATE "C". */
    private void seedNumbered(int count) {
        jdbc.sql("INSERT INTO sku (sku_id) SELECT 'p' || lpad(g::text, 3, '0') FROM generate_series(1, ?) g")
                .param(count).update();
    }

    private static List<String> numbered(int from, int to) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            ids.add(String.format("p%03d", i));
        }
        return ids;
    }

    private static List<String> skuIds(String json) {
        return JsonPath.read(json, "$[*].skuId");
    }

    private static URI next(MockHttpServletResponse response) {
        String link = response.getHeader(LINK);
        if (link == null) {
            return null;
        }
        assertThat(response.getHeaders(LINK)).as("one Link header").hasSize(1);
        Matcher matcher = LINK_VALUE.matcher(link);
        assertThat(matcher.matches()).as(link).isTrue();
        return URI.create(matcher.group(1));
    }

    /** One page: its items and, when present, the Link target. */
    record Page(List<Map<String, Object>> items, URI next) {
    }

    private static List<String> ids(Page page) {
        return page.items().stream().map(item -> (String) item.get("skuId")).toList();
    }

    private Page page(URI uri) throws Exception {
        MockHttpServletResponse response = list(uri)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        return new Page(JsonPath.read(response.getContentAsString(), "$"), next(response));
    }

    /** Follows Link from the first page until there is none; returns every page. */
    private List<Page> walk(URI first) throws Exception {
        List<Page> pages = new ArrayList<>();
        URI uri = first;
        while (uri != null) {
            assertThat(pages).as("pages walked").hasSizeLessThan(MAX_PAGES);
            Page page = page(uri);
            pages.add(page);
            uri = page.next();
        }
        return pages;
    }

    private long ledgerSum(String skuId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?")
                .param(skuId).query(Long.class).single();
    }

    private static long quantity(Map<String, Object> item) {
        return ((Number) item.get("quantity")).longValue();
    }

    /** AC2, D3: the quantities are the ledger SUM, including 0 and Long.MAX_VALUE. */
    @Test
    void pageQuantitiesMatchLedgerSum() throws Exception {
        seedMixed();

        List<Map<String, Object>> walked = page(URI.create("/inventory")).items();

        assertThat(walked).hasSize(MIXED.size());
        for (Map<String, Object> item : walked) {
            String skuId = (String) item.get("skuId");
            assertThat(quantity(item)).as(skuId).isEqualTo(ledgerSum(skuId));
        }
        assertThat(walked).extracting(i -> i.get("skuId"), InventoryPagingIntegrationTest::quantity).containsExactly(
                tuple("A-1", 5L),
                tuple("B-2", 12L),
                tuple("C-3", 3L),
                tuple("Z-9", Long.MAX_VALUE),
                tuple("a-1", 0L),
                tuple("b-2", 5L),
                tuple("c.3", 1L));
    }

    /**
     * OD-5, H4: limit is ignored in every form (usable, zero, non-numeric, repeated, past int): 250 rows and a Link
     * that carries only after, exactly as without it. InventoryServiceReadTest has no limit to parse on this path.
     */
    @ParameterizedTest(name = "?{0}")
    @ValueSource(strings = {"limit=5", "limit=0", "limit=abc", "limit=2&limit=3", "limit=99999999999999999999",
        "limit=2&foo=bar"})
    void limitIsIgnored(String query) throws Exception {
        seedNumbered(251);

        Page first = page(URI.create("/inventory?" + query));

        assertThat(ids(first)).containsExactlyElementsOf(numbered(1, 250));
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/inventory?after=p250"));
    }

    /**
     * R4, Z3: a single after containing a comma is one plain-string cursor, not a repeated after. "A-1,B-2" sorts
     * after "A-1" and before "B-2" in COLLATE "C", so the list starts at B-2.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/inventory?after=A-1%2CB-2", "/inventory?after=A-1,B-2"})
    void singleAfterWithCommaIsOneCursor(String query) throws Exception {
        seedMixed();

        Page page = page(URI.create(query));

        assertThat(ids(page)).containsExactly("B-2", "C-3", "Z-9", "a-1", "b-2", "c.3");
        assertThat(page.next()).isNull();
    }

    /**
     * AC4, R4, C2: after alone returns the SKUs after it (exclusive; the cursor need not exist), up to the default page
     * of 250; with 7 SKUs that is all of them and no Link.
     */
    @ParameterizedTest(name = "after={0}")
    @CsvSource(delimiter = '|', value = {
        "B-2 | C-3,Z-9,a-1,b-2,c.3",
        "B   | B-2,C-3,Z-9,a-1,b-2,c.3",
        "b-2 | c.3",
        "c.3 | ''",
        "zzz | ''",
        "''  | A-1,B-2,C-3,Z-9,a-1,b-2,c.3"
    })
    void afterAloneReturnsSkusAfterIt(String after, String expected) throws Exception {
        seedMixed();

        Page page = page(uri(after));

        assertThat(ids(page)).containsExactly(expected.isEmpty() ? new String[0] : expected.split(","));
        assertThat(page.next()).isNull();
    }

    /**
     * G11: the cursor compares in COLLATE "C" (byte) order: 'Z' < '_' < 'a', '-' < '.'. Two rows through the API;
     * SchemaTest#skuIdOrdersByCCollation pins the column's order.
     */
    @ParameterizedTest(name = "after={0}")
    @CsvSource(delimiter = '|', value = {
        "Z   | Z-9,a-1,b-2,c.3",
        "c-  | c.3"
    })
    void afterUsesCCollation(String after, String expected) throws Exception {
        seedMixed();

        assertThat(ids(page(uri(after)))).containsExactly(expected.split(","));
    }

    /** G9, C2: without params the response is the first page (here every SKU), a bare array, no Link. */
    @Test
    void noParamsReturnsBareArrayWithoutLink() throws Exception {
        seedMixed();

        list(URI.create("/inventory"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(
                        "[{\"skuId\":\"A-1\",\"quantity\":5},{\"skuId\":\"B-2\",\"quantity\":12},"
                                + "{\"skuId\":\"C-3\",\"quantity\":3},"
                                + "{\"skuId\":\"Z-9\",\"quantity\":9223372036854775807},"
                                + "{\"skuId\":\"a-1\",\"quantity\":0},{\"skuId\":\"b-2\",\"quantity\":5},"
                                + "{\"skuId\":\"c.3\",\"quantity\":1}]"))
                .andExpect(header().doesNotExist(LINK));
    }

    /** C2, H4: over 250 SKUs, no parameters → the first 250 and a Link with after only; the Link reaches the rest. */
    @Test
    void noParamsOver250ReturnsDefaultPageAndLink() throws Exception {
        seedNumbered(251);

        Page first = page(URI.create("/inventory"));

        assertThat(ids(first)).containsExactlyElementsOf(numbered(1, 250));
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/inventory?after=p250"));

        Page last = page(first.next());
        assertThat(ids(last)).containsExactly("p251");
        assertThat(last.next()).isNull();
    }

    /** C2, AC2: following Link from an unpaged GET visits every SKU exactly once, in pages of 250, after-only Links. */
    @Test
    void walkWithoutLimitVisitsEverySkuOnce() throws Exception {
        seedNumbered(600);

        List<Page> pages = walk(URI.create("/inventory"));

        assertThat(pages).extracting(p -> p.items().size()).containsExactly(250, 250, 100);
        assertThat(pages.stream().map(Page::next).filter(java.util.Objects::nonNull).toList()).containsExactly(
                URI.create("http://localhost/inventory?after=p250"), URI.create("http://localhost/inventory?after=p500"));
        assertThat(pages.stream().flatMap(p -> ids(p).stream()).toList())
                .containsExactlyElementsOf(numbered(1, 600))
                .doesNotHaveDuplicates();
        assertThat(pages.getLast().next()).isNull();
    }

    /** C2, R4, H4: after alone is capped at 250 with an after-only Link, even next to other parameters. */
    @Test
    void afterAloneOver250IsCapped() throws Exception {
        seedNumbered(300);

        Page first = page(URI.create("/inventory?foo=bar&limit=3&after=p010"));

        assertThat(ids(first)).containsExactlyElementsOf(numbered(11, 260));
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/inventory?after=p260"));

        Page last = page(first.next());
        assertThat(ids(last)).containsExactlyElementsOf(numbered(261, 300));
        assertThat(last.next()).isNull();
    }

    /** C2: exactly 250 SKUs without limit fill one page, which is the last: no Link. */
    @Test
    void exactly250HasNoLink() throws Exception {
        seedNumbered(250);

        Page page = page(URI.create("/inventory"));

        assertThat(ids(page)).containsExactlyElementsOf(numbered(1, 250));
        assertThat(page.next()).isNull();
    }

    /** G9, C2: an empty table is an empty array and no Link. */
    @Test
    void emptyTableReturnsEmptyArrayWithoutLink() throws Exception {
        list(URI.create("/inventory"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"))
                .andExpect(header().doesNotExist(LINK));
    }

    /** R4: after is cut at the first NUL, so %00 lists everything and abc%00x is after=abc. Never 500. */
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
        "/inventory?after=%00          | /inventory",
        "/inventory?after=B%00x        | /inventory?after=B"
    })
    void afterWithNulReturns200(String withNul, String equivalent) throws Exception {
        seedMixed();
        Page expected = page(URI.create(equivalent));

        Page actual = page(URI.create(withNul));

        assertThat(actual.items()).isEqualTo(expected.items());
        assertThat(actual.next()).isEqualTo(expected.next());
    }

    /** The cursor needs no validation: a value full of reserved characters is compared as a plain string. */
    @Test
    void afterNeedingEncodingIsAccepted() throws Exception {
        seedMixed();

        Page page = page(uri("a+b&c=d é/%"));

        // '+' (0x2B) sorts before '-' (0x2D), so the SKUs after the cursor start at a-1
        assertThat(ids(page)).containsExactly("a-1", "b-2", "c.3");
        assertThat(page.next()).isNull();
    }
}
