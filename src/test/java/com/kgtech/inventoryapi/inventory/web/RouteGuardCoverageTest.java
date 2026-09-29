package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * L21 guard (F-04, H11): every mapped route under /inventory or /v2/inventory that takes a {skuId} answers a ";" in
 * that segment with the frozen answer for its kind, and writes nothing. The routes come from the handler mapping, so
 * a route added later is covered without editing this class: if RoutedPath or InventoryRequestGuardFilter does not
 * know its shape, this test fails and says so. Freeze the rules, not the route list.
 */
@IntegrationTest
@AutoConfigureMockMvc
class RouteGuardCoverageTest {

    private static final String[] SKU_WITH_MATRIX = {"A;x", "A%3Bx"};
    private static final String[] BASES = {"/inventory", "/v2/inventory"};

    private record Answer(int status, String body) {
    }

    private static final Answer INVALID = new Answer(400, "Invalid request");
    private static final Answer NOT_FOUND = new Answer(404, "SKU not found");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void seed() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "A", 5);
    }

    /** H11: what a ";" (or %3B) in the SKU segment answers, by route kind and method; null when the guard has no answer. */
    private static Answer frozenAnswer(RouteKind kind, HttpMethod method) {
        boolean read = HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
        return switch (kind) {
            case ITEM -> read ? NOT_FOUND : HttpMethod.POST.equals(method) ? INVALID : null;
            case PURCHASE -> HttpMethod.POST.equals(method) ? NOT_FOUND : null;
            case DETAILS -> HttpMethod.PUT.equals(method) ? INVALID : null;
            case LIST, OTHER -> null;
        };
    }

    /** One entry per mapped (method, pattern) with {skuId} under a base; GET also stands for HEAD. */
    private List<Map.Entry<HttpMethod, String>> skuRoutes() {
        Set<String> seen = new TreeSet<>();
        List<Map.Entry<HttpMethod, String>> routes = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.contains("{skuId}") || !under(pattern)) {
                    continue;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                assertThat(methods).as("route %s maps every HTTP method; the guard needs an explicit method", pattern)
                        .isNotEmpty();
                for (RequestMethod method : methods) {
                    add(routes, seen, HttpMethod.valueOf(method.name()), pattern);
                    if (method == RequestMethod.GET) {
                        add(routes, seen, HttpMethod.HEAD, pattern); // Spring serves HEAD through the GET handler
                    }
                }
            }
        }
        return routes;
    }

    private static void add(List<Map.Entry<HttpMethod, String>> routes, Set<String> seen, HttpMethod method,
            String pattern) {
        if (seen.add(method + " " + pattern)) {
            routes.add(Map.entry(method, pattern));
        }
    }

    private static boolean under(String pattern) {
        for (String base : BASES) {
            if (pattern.startsWith(base + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Guards the guard: an empty enumeration (a changed mapping style, say) must not pass silently; OD-6 removed PUT item. */
    @Test
    void enumerationFindsTheKnownRoutes() {
        List<String> routes = skuRoutes().stream().map(e -> e.getKey() + " " + e.getValue()).toList();

        assertThat(routes).containsExactlyInAnyOrder("GET /inventory/{skuId}", "HEAD /inventory/{skuId}",
                "POST /inventory/{skuId}", "POST /inventory/{skuId}/purchase", "GET /v2/inventory/{skuId}",
                "HEAD /v2/inventory/{skuId}", "POST /v2/inventory/{skuId}", "POST /v2/inventory/{skuId}/purchase",
                "PUT /v2/inventory/{skuId}/details");
    }

    @TestFactory
    List<DynamicTest> everySkuRouteAnswersASemicolonSkuWithItsFrozenAnswerAndWritesNothing() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<HttpMethod, String> route : skuRoutes()) {
            for (String sku : SKU_WITH_MATRIX) {
                String path = route.getValue().replace("{skuId}", sku);
                tests.add(dynamicTest(route.getKey() + " " + path, () -> check(route.getKey(), route.getValue(), path)));
            }
        }
        return tests;
    }

    private void check(HttpMethod method, String pattern, String path) throws Exception {
        RoutedPath routed = RoutedPath.parse(path, "").orElse(null);
        String unguarded = "Route " + method + " " + pattern + " takes a {skuId} but the guard does not know it: "
                + "teach RoutedPath its shape (RouteKind) and InventoryRequestGuardFilter its frozen answer (L21, H11)";
        assertThat(routed).as(unguarded + " (not under an inventory base)").isNotNull();
        assertThat(routed.kind()).as(unguarded + " (classified " + routed.kind() + ")").isNotEqualTo(RouteKind.OTHER);
        assertThat(routed.rawSkuId()).as("the SKU segment keeps its ';' content").contains(";");
        assertThat(routed.skuHasMatrix()).isTrue();
        assertThat(routed.v2()).isEqualTo(pattern.startsWith("/v2/"));
        Answer expected = frozenAnswer(routed.kind(), method);
        assertThat(expected).as(unguarded + " (no frozen answer for " + routed.kind() + " " + method + ")").isNotNull();

        Map<String, Long> before = Tables.counts(jdbc);
        MockHttpServletResponse response = mvc.perform(build(method, path)).andReturn().getResponse();

        assertThat(response.getStatus()).as("%s %s", method, path).isEqualTo(expected.status());
        assertThat(response.getContentType()).startsWith("text/plain");
        if (!HttpMethod.HEAD.equals(method)) {
            assertThat(response.getContentAsString()).isEqualTo(expected.body());
        }
        assertThat(Tables.counts(jdbc)).as("rows written by %s %s", method, path).isEqualTo(before);
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'A'").query(Long.class).single()).isEqualTo(5);
    }

    /** A well-formed request with a valid key: only the ';' in the SKU segment may stop it. */
    private static MockHttpServletRequestBuilder build(HttpMethod method, String path) {
        MockHttpServletRequestBuilder request = request(method, URI.create(path)).accept(APPLICATION_JSON);
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
            return request;
        }
        return request.contentType(APPLICATION_JSON).content("{\"quantity\":1}")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString());
    }
}
