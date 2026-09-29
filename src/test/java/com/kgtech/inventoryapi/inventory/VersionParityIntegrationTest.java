package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * Invariants 6 (version parity) and 7 (bounded lists) through the real filters: more than 250 SKUs written through a
 * mix of unversioned adds and purchases, keyed /v2 adds and purchases, and PUT-created SKUs at 0; then both lists are
 * walked by their Links and must show the same (skuId, quantity) pairs in the same order, none of them more than 250
 * to a page. Single reads agree after further interleaved writes. The per-rule paging matrices live in
 * InventoryPagingIntegrationTest and V2ListPagingIntegrationTest; this class only crosses the two versions.
 */
@IntegrationTest
@AutoConfigureMockMvc
class VersionParityIntegrationTest {

    private static final int PAGE_MAX = 250;
    private static final int SKUS = 620;
    private static final Pattern LINK_VALUE = Pattern.compile("^<([^>]+)>; rel=\"next\"$");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /** What each SKU must hold, kept beside the writes so the lists are checked against more than each other. */
    private final Map<String, Long> expected = new TreeMap<>();

    private record Item(String skuId, long quantity) {
    }

    private record Page(List<Item> items, URI next) {
    }

    @BeforeEach
    void seedThroughBothVersions() throws Exception {
        Tables.reset(jdbc);
        expected.clear();
        for (int i = 0; i < SKUS; i++) {
            String sku = String.format("%s-%03d", i % 2 == 0 ? "sku" : "SKU", i);
            switch (i % 5) {
                case 0 -> add(sku, i % 7 + 2, false);
                case 1 -> add(sku, i % 7 + 2, true);
                case 2 -> putDetails(sku); // the SKU exists at 0
                case 3 -> {
                    add(sku, 5, false);
                    purchase(sku, 2, true);
                }
                default -> {
                    add(sku, 5, true);
                    purchase(sku, 3, false);
                    if (i % 2 == 0) {
                        add(sku, 1, false);
                    }
                }
            }
        }
    }

    private void add(String sku, int quantity, boolean v2) throws Exception {
        write(v2 ? "/v2/inventory/" + sku : "/inventory/" + sku, quantity, v2);
        expected.merge(sku, (long) quantity, Long::sum);
    }

    private void purchase(String sku, int quantity, boolean v2) throws Exception {
        write((v2 ? "/v2/inventory/" : "/inventory/") + sku + "/purchase", quantity, v2);
        expected.merge(sku, (long) -quantity, Long::sum);
    }

    /** A 200 write; the /v2 form carries a fresh key, the unversioned form must not carry one. */
    private void write(String path, int quantity, boolean v2) throws Exception {
        var request = post(path).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":" + quantity + "}");
        if (v2) {
            request.header(IDEMPOTENCY_KEY, UUID.randomUUID().toString());
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s: %s", path, response.getContentAsString()).isEqualTo(200);
    }

    private void putDetails(String sku) throws Exception {
        MockHttpServletResponse response = mvc.perform(put("/v2/inventory/{sku}/details", sku)
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Created by PUT\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        expected.put(sku, 0L);
    }

    private Page page(URI uri) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(uri).accept(MediaType.APPLICATION_JSON)).andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(uri.toString()).isEqualTo(200);
        String body = response.getContentAsString();
        List<String> ids = JsonPath.read(body, "$[*].skuId");
        List<Number> quantities = JsonPath.read(body, "$[*].quantity");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            items.add(new Item(ids.get(i), quantities.get(i).longValue()));
        }
        String link = response.getHeader(LINK);
        if (link == null) {
            return new Page(items, null);
        }
        Matcher matcher = LINK_VALUE.matcher(link);
        assertThat(matcher.matches()).as(link).isTrue();
        return new Page(items, URI.create(matcher.group(1)));
    }

    /** Follows Link from the first page to the last, checking the bound on every page; returns the items in order. */
    private List<Item> walk(String first) throws Exception {
        List<Item> all = new ArrayList<>();
        int pages = 0;
        for (URI uri = URI.create(first); uri != null; ) {
            assertThat(++pages).as("pages walked").isLessThan(20);
            Page page = page(uri);
            assertThat(page.items()).as("invariant 7: %s holds at most %d items", uri, PAGE_MAX)
                    .hasSizeLessThanOrEqualTo(PAGE_MAX);
            all.addAll(page.items());
            uri = page.next();
        }
        return all;
    }

    private long quantityOf(String path) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)).andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        return ((Number) JsonPath.read(response.getContentAsString(), "$.quantity")).longValue();
    }

    @Test
    void walkingBothListsByTheirLinksVisitsTheSameSkusAndQuantitiesInTheSameOrder() throws Exception {
        List<Item> unversioned = walk("/inventory");
        List<Item> v2 = walk("/v2/inventory?limit=250");

        assertThat(unversioned).hasSize(SKUS);
        assertThat(v2).isEqualTo(unversioned);
        assertThat(unversioned.stream().map(Item::skuId).toList()).isSorted().doesNotHaveDuplicates();
        assertThat(unversioned).allSatisfy(item -> assertThat(item.quantity())
                .as(item.skuId()).isEqualTo(expected.get(item.skuId())));
    }

    @Test
    void singleReadsAgreeAcrossVersionsAfterInterleavedWrites() throws Exception {
        List<String> sample = new ArrayList<>();
        for (int i = 0; i < SKUS; i += 31) {
            sample.addAll(expected.keySet().stream().skip(i).limit(1).toList());
        }
        for (int round = 0; round < 3; round++) {
            for (String sku : sample) {
                switch (round) {
                    case 0 -> add(sku, 4, true);
                    case 1 -> purchase(sku, 1, false);
                    default -> add(sku, 2, false);
                }
                assertThat(quantityOf("/inventory/" + sku)).as("unversioned %s, round %d", sku, round)
                        .isEqualTo(expected.get(sku));
                assertThat(quantityOf("/v2/inventory/" + sku)).as("/v2 %s, round %d", sku, round)
                        .isEqualTo(expected.get(sku));
            }
        }
        assertThat(walk("/v2/inventory")).isEqualTo(walk("/inventory"));
    }

    /** A limit past the maximum is 250 on /v2 and is ignored on the unversioned list (OD-5): 250 either way. */
    @Test
    void anOversizedLimitStillReturnsAtMost250() throws Exception {
        assertThat(page(URI.create("/v2/inventory?limit=1000000")).items()).hasSize(PAGE_MAX);
        assertThat(page(URI.create("/inventory?limit=1000000")).items()).hasSize(PAGE_MAX);
    }

    /**
     * Invariant 7 by enumeration: the only GET routes that answer with a list are the two collection routes, and both
     * are walked above. A collection route added later fails here until it is bounded and added to this set; no
     * handler may return a bare collection (an unpaged body).
     */
    @Test
    void everyCollectionRouteIsOneOfTheBoundedLists() {
        List<String> collectionRoutes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> route : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = route.getKey();
            assertThat(info.getPathPatternsCondition()).isNotNull();
            boolean get = info.getMethodsCondition().getMethods().stream()
                    .anyMatch(method -> HttpMethod.GET.name().equals(method.name()));
            if (!get) {
                continue;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if ((pattern.startsWith("/inventory") || pattern.startsWith("/v2/inventory"))
                        && !pattern.contains("{")) {
                    collectionRoutes.add(pattern);
                }
            }
            assertThat(Collection.class.isAssignableFrom(route.getValue().getMethod().getReturnType()))
                    .as("%s returns a bare collection", route.getValue()).isFalse();
        }

        assertThat(collectionRoutes).containsExactlyInAnyOrder("/inventory", "/v2/inventory");
    }
}
