package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
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
    /** Upper and lower case, a leading digit and every punctuation mark the pattern allows, so COLLATE "C" order shows. */
    private static final String[] ID_FORMATS = {"sku-%03d", "SKU-%03d", "9-lot-%03d", "a.b-%03d", "Z_%03d", "0.%03d"};
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
            String sku = String.format(ID_FORMATS[i % ID_FORMATS.length], i);
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

    /** Invariant 6 "for every SKU": after further interleaved writes, both single reads of all SKUs match the model. */
    @Test
    void everySkuReadsTheSameThroughBothVersionsAfterInterleavedWrites() throws Exception {
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
            }
        }

        assertThat(expected).hasSize(SKUS);
        expected.forEach((sku, quantity) -> {
            try {
                assertThat(quantityOf("/inventory/" + sku)).as("unversioned %s", sku).isEqualTo(quantity);
                assertThat(quantityOf("/v2/inventory/" + sku)).as("/v2 %s", sku).isEqualTo(quantity);
            } catch (Exception e) {
                throw new AssertionError(sku, e);
            }
        });
        assertThat(walk("/v2/inventory?limit=250")).isEqualTo(walk("/inventory"));
    }

    /** A limit past the maximum is 250 on /v2 and is ignored on the unversioned list (OD-5): 250 either way. */
    @Test
    void anOversizedLimitStillReturnsAtMost250() throws Exception {
        assertThat(page(URI.create("/v2/inventory?limit=1000000")).items()).hasSize(PAGE_MAX);
        assertThat(page(URI.create("/inventory?limit=1000000")).items()).hasSize(PAGE_MAX);
    }

    /**
     * Invariant 7 by allow-list: the application's whole GET surface is these four routes. Only the two collection
     * routes can answer with a list, and the walks above prove both are paged at 250 or less. Library mappings
     * (actuator, springdoc, Swagger UI, the error controller) are excluded by handler class: only handlers declared
     * under com.kgtech.inventoryapi count. A method-less mapping answers GET too, so it counts as GET.
     */
    @Test
    void theOnlyGetRoutesAreTheTwoPagedListsAndTheirItems() {
        Set<String> routes = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> route : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = route.getKey();
            assertThat(info.getPathPatternsCondition()).isNotNull();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (!route.getValue().getBeanType().getPackageName().startsWith("com.kgtech.inventoryapi")
                    || !(methods.isEmpty() || methods.contains(RequestMethod.GET))) {
                continue;
            }
            info.getPathPatternsCondition().getPatternValues().forEach(pattern -> routes.add("GET " + pattern));
        }

        assertThat(routes).as("A new GET route is unproven for invariant 7. Prove it bounded (paged with limit <= 250, "
                + "walked in this class) and add it here.")
                .containsExactly("GET /inventory", "GET /inventory/{skuId}", "GET /v2/inventory",
                        "GET /v2/inventory/{skuId}");
    }
}
