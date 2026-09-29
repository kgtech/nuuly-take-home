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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * G9, R4, R8, C2, H5 against Postgres, moved from the unversioned list (F-07): GET /v2/inventory pages with a lenient
 * limit (1-250, default 250, anything unusable or repeated is ignored) and after, and its Link
 * (/v2/inventory?limit=..&amp;after=..) carries both. The unversioned list keeps only the limit-free rules
 * (InventoryPagingIntegrationTest); the 250-default walk, the row-for-row parity and the repeated-after 400 are
 * SkuDetailsApiIntegrationTest's. Not @Transactional: the tables are emptied before each test.
 */
@IntegrationTest
@AutoConfigureMockMvc
class V2ListPagingIntegrationTest {

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
        Tables.reset(jdbc);
    }

    /** Seeds through the spec's add, which any version's list then shows. */
    private void create(String skuId, int quantity) throws Exception {
        mvc.perform(post("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    private void seedMixed() throws Exception {
        create("c.3", 1);
        create("a-1", 4);
        create("Z-9", 1);
        create("b-2", 5);
        create("C-3", 5);
        create("B-2", 5);
        create("B-2", 7);
        create("A-1", 5);
    }

    private static URI uri(String limit, String after) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/v2/inventory");
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

    private String defaultPageBody() throws Exception {
        return list(URI.create("/v2/inventory"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(LINK))
                .andReturn().getResponse().getContentAsString();
    }

    /** Seeds count SKUs p001, p002, … with no ledger rows (quantity 0); ids sort numerically in COLLATE "C". */
    private void seedNumbered(int count) {
        jdbc.sql("INSERT INTO sku (sku_id) SELECT 'p' || lpad(g::text, 3, '0') FROM generate_series(1, ?) g")
                .param(count).update();
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

    private static long quantity(Map<String, Object> item) {
        return ((Number) item.get("quantity")).longValue();
    }

    /** AC1: following Link visits every SKU exactly once, in order; only the last page lacks a Link. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 6, 7, 8, 250})
    void walkFollowingLinkVisitsEverySkuOnce(int limit) throws Exception {
        seedMixed();
        String unpaged = defaultPageBody();

        List<Page> pages = walk(uri(Integer.toString(limit), null));

        List<Map<String, Object>> walked = pages.stream().flatMap(p -> p.items().stream()).toList();
        assertThat(walked).isEqualTo(JsonPath.<List<Map<String, Object>>>read(unpaged, "$"));
        assertThat(walked).extracting(i -> i.get("skuId"), V2ListPagingIntegrationTest::quantity).containsExactly(
                tuple("A-1", 5L), tuple("B-2", 12L), tuple("C-3", 5L), tuple("Z-9", 1L), tuple("a-1", 4L),
                tuple("b-2", 5L), tuple("c.3", 1L));
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
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/v2/inventory?limit=2&after=B"));

        Page second = page(first.next());
        assertThat(ids(second)).containsExactly("C", "D");
        assertThat(second.next()).isNull();
    }

    /**
     * AC3, R4, C2: an unusable limit is ignored: 200, the default page (here every SKU), no Link. Never 400. A repeated
     * limit arrives as "2,3", which is not a number, so it is ignored too (unlike a repeated after).
     */
    @ParameterizedTest(name = "?{0}")
    @ValueSource(strings = {"limit=0", "limit=abc", "limit=99999999999999999999", "limit=2&limit=3"})
    void lenientLimitAlwaysReturns200(String query) throws Exception {
        seedMixed();
        String unpaged = defaultPageBody();

        list(URI.create("/v2/inventory?" + query))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(unpaged, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(LINK));
    }

    /** AC3, R8: limit above 250, even past int and long, is 250; the Link carries limit=250. */
    @ParameterizedTest
    @ValueSource(strings = {"251", "9999", "99999999999", "99999999999999999999"})
    void limitAboveMaxIsClampedTo250(String limit) throws Exception {
        seedNumbered(251);

        Page first = page(uri(limit, null));

        assertThat(first.items()).hasSize(250);
        assertThat(first.items().getLast().get("skuId")).isEqualTo("p250");
        assertThat(first.next()).isEqualTo(URI.create("http://localhost/v2/inventory?limit=250&after=p250"));

        Page last = page(first.next());
        assertThat(ids(last)).containsExactly("p251");
        assertThat(last.next()).isNull();
    }

    /** G9: limit and after together give the next N after the cursor, with a Link while more remain. */
    @Test
    void limitWithAfterPagesFromCursor() throws Exception {
        seedMixed();

        Page page = page(uri("2", "B-2"));

        assertThat(ids(page)).containsExactly("C-3", "Z-9");
        assertThat(page.next()).isEqualTo(URI.create("http://localhost/v2/inventory?limit=2&after=Z-9"));
    }

    /** R4, Z3: a single after containing a comma is one cursor; with a limit it pages from there. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/v2/inventory?limit=2&after=A-1%2CB-2", "/v2/inventory?limit=2&after=A-1,B-2"})
    void limitWithSingleAfterWithCommaPagesFromCursor(String query) throws Exception {
        seedMixed();

        Page page = page(URI.create(query));

        assertThat(ids(page)).containsExactly("B-2", "C-3");
        assertThat(page.next()).isEqualTo(URI.create("http://localhost/v2/inventory?limit=2&after=C-3"));
    }

    /** R4: after is cut at the first NUL next to a limit, and never answers 500. */
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
        "/v2/inventory?limit=2&after=%00  | /v2/inventory?limit=2",
        "/v2/inventory?limit=2&after=B%00 | /v2/inventory?limit=2&after=B"
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
        assertThat(page.next()).isEqualTo(URI.create("http://localhost/v2/inventory?limit=1&after=a-1"));
    }
}
