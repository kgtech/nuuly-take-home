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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * G9, R4, R8, AC1–AC4 against Postgres (S11): keyset pages over the sku table in COLLATE "C" order, balances from the
 * ledger SUM, a Link on every page but the last, and a lenient limit. Not @Transactional: every request commits its
 * own transaction, so the tables are emptied before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
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
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES ('Z-9', ?, 'add')")
                .param(Long.MAX_VALUE - 1).update(); // the API can't reach the limit
        create("b-2", 5);
        create("C-3", 5);
        purchase("C-3", 2);
        create("B-2", 5);
        create("B-2", 7);
        create("A-1", 5);
    }

    private static URI uri(String limit, String after) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/inventory");
        if (limit != null) {
            builder.queryParam("limit", "{limit}");
        }
        if (after != null) {
            builder.queryParam("after", "{after}");
        }
        return builder.encode()
                .buildAndExpand(Map.of("limit", limit == null ? "" : limit, "after", after == null ? "" : after))
                .toUri();
    }

    private ResultActions list(URI uri) throws Exception {
        return mvc.perform(get(uri).accept(MediaType.APPLICATION_JSON));
    }

    private String unpagedBody() throws Exception {
        return list(URI.create("/inventory"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(LINK))
                .andReturn().getResponse().getContentAsString();
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

    /** AC1: following Link visits every SKU exactly once, in order; only the last page lacks a Link. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 6, 7, 8, 250})
    void walkFollowingLinkVisitsEverySkuOnce(int limit) throws Exception {
        seedMixed();
        String unpaged = unpagedBody();
        assertThat(skuIds(unpaged)).containsExactlyElementsOf(MIXED);

        List<Page> pages = walk(uri(Integer.toString(limit), null));

        List<Map<String, Object>> walked = pages.stream().flatMap(p -> p.items().stream()).toList();
        assertThat(walked).isEqualTo(JsonPath.<List<Map<String, Object>>>read(unpaged, "$"));
        assertThat(pages).hasSize(Math.max(1, (MIXED.size() + limit - 1) / limit));
        for (int i = 0; i < pages.size() - 1; i++) {
            assertThat(pages.get(i).items()).as("page %d", i).hasSize(limit);
            assertThat(pages.get(i).next()).as("page %d Link", i).isNotNull();
        }
        assertThat(pages.getLast().next()).as("last page Link").isNull();
    }

    /** AC1: exactly limit SKUs left gives a full last page with no Link. */
    @Test
    void lastFullPageHasNoLink() throws Exception {
        for (String sku : List.of("A", "B", "C", "D")) {
            create(sku, 1);
        }

        Page first = page(uri("2", null));
        assertThat(ids(first)).containsExactly("A", "B");
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/inventory?limit=2&after=B"));

        Page second = page(first.next());
        assertThat(ids(second)).containsExactly("C", "D");
        assertThat(second.next()).isNull();
    }

    /** AC2, D3: every page's quantities are the ledger SUM, including 0 and Long.MAX_VALUE. */
    @Test
    void pageQuantitiesMatchLedgerSum() throws Exception {
        seedMixed();

        List<Map<String, Object>> walked = walk(uri("2", null)).stream().flatMap(p -> p.items().stream()).toList();

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

    /** AC3, R4: an unusable limit is ignored: 200, every SKU, no Link. Never 400. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0", "abc", "", " ", "1.5", " 5", "5 ", "1e3", "٣"})
    void lenientLimitAlwaysReturns200(String limit) throws Exception {
        seedMixed();
        String unpaged = unpagedBody();

        list(uri(limit, null))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(unpaged, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(LINK));
    }

    /** R4, Z3: a repeated limit arrives as "2,3", which is not a number, so it is ignored (unlike a repeated after). */
    @Test
    void repeatedLimitIsIgnored() throws Exception {
        seedMixed();
        String unpaged = unpagedBody();

        list(URI.create("/inventory?limit=2&limit=3"))
                .andExpect(status().isOk())
                .andExpect(content().json(unpaged, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(LINK));
    }

    /** Z3: a repeated after is 400 text/plain "Invalid request"; the cursor must be one sku_id. */
    @ParameterizedTest(name = "?{0} → 400")
    @ValueSource(strings = {"after=A-1&after=B-2", "limit=2&after=A-1&after=B-2", "after=&after=B-2"})
    void repeatedAfterReturns400(String query) throws Exception {
        seedMixed();

        list(URI.create("/inventory?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Invalid request"))
                .andExpect(header().doesNotExist(LINK));
    }

    /** AC3, R8: limit above 250, even past int and long, is 250; the Link carries limit=250. */
    @ParameterizedTest
    @ValueSource(strings = {"251", "9999", "99999999999", "99999999999999999999"})
    void limitAboveMaxIsClampedTo250(String limit) throws Exception {
        jdbc.sql("INSERT INTO sku (sku_id) SELECT 'p' || lpad(g::text, 3, '0') FROM generate_series(1, 251) g")
                .update();

        Page first = page(uri(limit, null));

        assertThat(first.items()).hasSize(250);
        assertThat(first.items().getLast().get("skuId")).isEqualTo("p250");
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/inventory?limit=250&after=p250"));

        Page last = page(first.next());
        assertThat(ids(last)).containsExactly("p251");
        assertThat(last.next()).isNull();
    }

    /** AC4, R4: after alone returns every SKU after it (exclusive; the cursor need not exist) and no Link. */
    @ParameterizedTest(name = "after={0}")
    @CsvSource(delimiter = '|', value = {
        "B-2 | C-3,Z-9,a-1,b-2,c.3",
        "B   | B-2,C-3,Z-9,a-1,b-2,c.3",
        "b-2 | c.3",
        "c.3 | ''",
        "zzz | ''",
        "''  | A-1,B-2,C-3,Z-9,a-1,b-2,c.3"
    })
    void afterAloneReturnsEverySkuAfterIt(String after, String expected) throws Exception {
        seedMixed();

        Page page = page(uri(null, after));

        assertThat(ids(page)).containsExactly(expected.isEmpty() ? new String[0] : expected.split(","));
        assertThat(page.next()).isNull();
    }

    /** G9: limit and after together give the next N after the cursor, with a Link while more remain. */
    @Test
    void limitWithAfterPagesFromCursor() throws Exception {
        seedMixed();

        Page page = page(uri("2", "B-2"));

        assertThat(ids(page)).containsExactly("C-3", "Z-9");
        assertThat(page.next()).isEqualTo(URI.create("http://localhost/inventory?limit=2&after=Z-9"));
    }

    /** G11: the cursor compares in COLLATE "C" (byte) order: 'Z' < '_' < 'a', '-' < '.'. */
    @ParameterizedTest(name = "after={0}")
    @CsvSource(delimiter = '|', value = {
        "Z   | Z-9,a-1,b-2,c.3",
        "_   | a-1,b-2,c.3",
        "Z-: | a-1,b-2,c.3",
        "c-  | c.3",
        "a   | a-1,b-2,c.3"
    })
    void afterUsesCCollation(String after, String expected) throws Exception {
        seedMixed();

        assertThat(ids(page(uri(null, after)))).containsExactly(expected.split(","));
    }

    /** G9: without params the response is today's: every SKU, a bare array, no Link. */
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

    @Test
    void emptyTableWithLimitReturnsEmptyArrayWithoutLink() throws Exception {
        list(uri("2", null))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"))
                .andExpect(header().doesNotExist(LINK));
    }

    /** R4: after is cut at the first NUL, so %00 lists everything and abc%00x is after=abc. Never 500. */
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
        "/inventory?after=%00          | /inventory",
        "/inventory?after=B%00x        | /inventory?after=B",
        "/inventory?limit=2&after=%00  | /inventory?limit=2",
        "/inventory?limit=2&after=B%00 | /inventory?limit=2&after=B"
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

        Page page = page(uri("1", "a+b&c=d é/%"));

        // '+' (0x2B) sorts before '-' (0x2D), so the next SKU is a-1
        assertThat(ids(page)).containsExactly("a-1");
        assertThat(page.next()).isEqualTo(URI.create("http://localhost/inventory?limit=1&after=a-1"));
    }

    /** U2: a paged GET ignores Accept: application/xml and still answers JSON with its Link. */
    @Test
    void pagedGetIgnoresXmlAccept() throws Exception {
        seedMixed();

        mvc.perform(get(uri("2", null)).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(
                        "[{\"skuId\":\"A-1\",\"quantity\":5},{\"skuId\":\"B-2\",\"quantity\":12}]",
                        JsonCompareMode.STRICT))
                .andExpect(header().string(LINK, "<http://localhost/inventory?limit=2&after=B-2>; rel=\"next\""));
    }
}
