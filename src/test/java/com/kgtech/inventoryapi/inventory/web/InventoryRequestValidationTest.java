package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.InventoryPage;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.WriteResult;

/**
 * Request validation at the HTTP edge: G13/G3 bodies (AC3), G4/U3 ordering, U2/Y1/C3 Accept handling. Every body or
 * Accept 400 is text/plain "Invalid request" and never reaches the service. G11/S2/C3 (Z1): the controller passes the
 * raw skuId segment, ";" content included, to the service unchanged and maps the service's outcome; the no-I/O check
 * is in the service tests.
 */
@WebMvcTest(InventoryController.class)
class InventoryRequestValidationTest {

    private static final String SKU = "widget";
    private static final String VALID_BODY = "{\"quantity\":5}";

    /** The two POST operations. */
    enum Post {
        CREATE("/inventory/{skuId}"),
        PURCHASE("/inventory/{skuId}/purchase");

        final String template;

        Post(String template) {
            this.template = template;
        }

        void stubOk(InventoryService service, String skuId, int quantity) {
            if (this == CREATE) {
                when(service.add(skuId, quantity, null)).thenReturn(new StockOutcome.Ok(quantity));
            } else {
                when(service.purchase(skuId, quantity, null)).thenReturn(new StockOutcome.Ok(quantity));
            }
        }

        void verifyCalled(InventoryService service, String skuId, int quantity) {
            if (this == CREATE) {
                verify(service).add(skuId, quantity, null);
            } else {
                verify(service).purchase(skuId, quantity, null);
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private static MockHttpServletRequestBuilder jsonPost(Post op, String skuId, String body) {
        return post(op.template, skuId).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void expectText(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }

    private static void expectInvalidRequest(ResultActions result) throws Exception {
        expectText(result, 400, "Invalid request");
    }

    private static void expectItem(ResultActions result, String skuId, long quantity) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}",
                        JsonCompareMode.STRICT));
    }

    // --- G13 and G3 (AC3) ---

    /** name, Content-Type (null = none), body (null = none). */
    private static final List<String[]> INVALID_BODIES = List.of(
            // required by AC3
            new String[] {"string quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":\"10\"}"},
            new String[] {"float quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":10.5}"},
            new String[] {"null quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":null}"},
            new String[] {"empty object", MediaType.APPLICATION_JSON_VALUE, "{}"},
            new String[] {"missing body", MediaType.APPLICATION_JSON_VALUE, null},
            new String[] {"text/xml content type", MediaType.TEXT_XML_VALUE, VALID_BODY},
            // extra edge cases (OQ8)
            new String[] {"no content type", null, VALID_BODY},
            new String[] {"zero quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":0}"},
            new String[] {"negative quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":-1}"},
            new String[] {"quantity above int max", MediaType.APPLICATION_JSON_VALUE,
                "{\"quantity\":2147483648}"},
            new String[] {"boolean quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":true}"},
            new String[] {"non-numeric string quantity", MediaType.APPLICATION_JSON_VALUE,
                "{\"quantity\":\"abc\"}"},
            new String[] {"whole float quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":10.0}"},
            new String[] {"exponent quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":1e1}"},
            new String[] {"array quantity", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":[5]}"},
            new String[] {"JSON null body", MediaType.APPLICATION_JSON_VALUE, "null"},
            new String[] {"malformed JSON", MediaType.APPLICATION_JSON_VALUE, "{\"quantity\":"},
            new String[] {"empty string body", MediaType.APPLICATION_JSON_VALUE, ""},
            new String[] {"form-urlencoded", MediaType.APPLICATION_FORM_URLENCODED_VALUE, "quantity=5"});

    static Stream<Arguments> invalidBodyReturns400OnBothPosts() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            for (String[] invalid : INVALID_BODIES) {
                cases.add(Arguments.of(op + ": " + invalid[0], op, invalid[1], invalid[2]));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void invalidBodyReturns400OnBothPosts(String name, Post op, String contentType, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(op.template, SKU).accept(MediaType.APPLICATION_JSON);
        if (contentType != null) {
            request.contentType(contentType);
        }
        if (body != null) {
            request.content(body);
        }

        expectInvalidRequest(mvc.perform(request));
        verifyNoInteractions(service);
    }

    /** G13: OpenAPI 3.0 allows unknown properties, so they are ignored. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void unknownPropertyIsIgnoredOnBothPosts(Post op) throws Exception {
        op.stubOk(service, SKU, 5);

        expectItem(mvc.perform(jsonPost(op, SKU, "{\"quantity\":5,\"extra\":1}")), SKU, 5);
        op.verifyCalled(service, SKU, 5);
    }

    /** V2: the request quantity is an Integer up to 2,147,483,647. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void maxIntQuantityIsAccepted(Post op) throws Exception {
        op.stubOk(service, SKU, Integer.MAX_VALUE);

        expectItem(mvc.perform(jsonPost(op, SKU, "{\"quantity\":2147483647}")), SKU, Integer.MAX_VALUE);
        op.verifyCalled(service, SKU, Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @EnumSource(Post.class)
    void jsonWithCharsetIsAccepted(Post op) throws Exception {
        op.stubOk(service, SKU, 5);

        expectItem(mvc.perform(post(op.template, SKU).accept(MediaType.APPLICATION_JSON)
                .contentType("application/json;charset=UTF-8").content(VALID_BODY)), SKU, 5);
        op.verifyCalled(service, SKU, 5);
    }

    // --- G11 and S2 ---

    static Stream<String> invalidSkuIds() {
        return Stream.of("-bad", "a".repeat(65), "a!b");
    }

    @ParameterizedTest
    @MethodSource("invalidSkuIds")
    void createPassesInvalidSkuIdToServiceAndReturns400(String skuId) throws Exception {
        when(service.add(skuId, 5, null)).thenReturn(new WriteResult.InvalidRequest());

        expectInvalidRequest(mvc.perform(jsonPost(Post.CREATE, skuId, VALID_BODY)));
        verify(service).add(skuId, 5, null);
    }

    @ParameterizedTest
    @MethodSource("invalidSkuIds")
    void getPassesInvalidSkuIdToServiceAndReturns404(String skuId) throws Exception {
        when(service.find(skuId)).thenReturn(Optional.empty());

        expectText(mvc.perform(get("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON)),
                404, "SKU not found");
        verify(service).find(skuId);
    }

    @ParameterizedTest
    @MethodSource("invalidSkuIds")
    void purchasePassesInvalidSkuIdToServiceAndReturns404(String skuId) throws Exception {
        when(service.purchase(skuId, 5, null)).thenReturn(new StockOutcome.NotFound());

        expectText(mvc.perform(jsonPost(Post.PURCHASE, skuId, VALID_BODY)), 404, "SKU not found");
        verify(service).purchase(skuId, 5, null);
    }

    @Test
    void maxLengthSkuIdIsAccepted() throws Exception {
        String skuId = "a".repeat(64);
        when(service.find(skuId)).thenReturn(Optional.of(new InventoryItem(skuId, 5)));
        Post.CREATE.stubOk(service, skuId, 5);
        Post.PURCHASE.stubOk(service, skuId, 5);

        expectItem(mvc.perform(get("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON)), skuId, 5);
        expectItem(mvc.perform(jsonPost(Post.CREATE, skuId, VALID_BODY)), skuId, 5);
        expectItem(mvc.perform(jsonPost(Post.PURCHASE, skuId, VALID_BODY)), skuId, 5);
    }

    /**
     * G11, S2, C3: the service gets the skuId segment as sent, percent-decoded with its ";" content, never Spring's
     * stripped @PathVariable. ";" on the literal segments is ignored. Each request is built with URI.create so the
     * raw path reaches the DispatcherServlet unchanged; the service stubs give each operation's rejection.
     */
    @ParameterizedTest(name = "{0} {1} → service gets {2}")
    @CsvSource(delimiter = '|', value = {
        "GET      | /inventory/ABC-1;x=y              | ABC-1;x=y",
        "GET      | /inventory/ABC-1;                 | ABC-1;",
        "GET      | /inventory/ABC-1%3Bx=y            | ABC-1;x=y",
        "GET      | /inventory;v=1/ABC-1              | ABC-1",
        "CREATE   | /inventory/ABC-1;lot=7            | ABC-1;lot=7",
        "CREATE   | /inventory/ABC-1;                 | ABC-1;",
        "CREATE   | /inventory/ABC-1%3Bx              | ABC-1;x",
        "CREATE   | /inventory;v=1/ABC-1              | ABC-1",
        "PURCHASE | /inventory/ABC-1;x/purchase       | ABC-1;x",
        "PURCHASE | /%69nventory/ABC-1;x/purchase     | ABC-1;x",
        "PURCHASE | /inventory/ABC-1%3Bx/purchase     | ABC-1;x"
    })
    void skuIdSegmentKeepsSemicolonContent(String op, String rawPath, String expectedSkuId) throws Exception {
        when(service.find(anyString())).thenReturn(Optional.empty());
        when(service.add(anyString(), anyInt(), isNull())).thenReturn(new WriteResult.InvalidRequest());
        when(service.purchase(anyString(), anyInt(), isNull())).thenReturn(new StockOutcome.NotFound());
        URI uri = URI.create(rawPath);
        MockHttpServletRequestBuilder request = "GET".equals(op)
                ? get(uri).accept(MediaType.APPLICATION_JSON)
                : post(uri).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY);

        ResultActions result = mvc.perform(request);
        MvcResult sent = result.andReturn();

        assertThat(sent.getRequest().getRequestURI()).as("raw path sent").isEqualTo(rawPath);
        switch (op) {
            case "GET" -> verify(service).find(expectedSkuId);
            case "CREATE" -> verify(service).add(expectedSkuId, 5, null);
            default -> verify(service).purchase(expectedSkuId, 5, null);
        }
        if ("CREATE".equals(op)) {
            expectInvalidRequest(result);
        } else {
            expectText(result, 404, "SKU not found");
        }
    }

    /** C3: under a context path the skuId is still the segment after /inventory, ";" content kept. */
    @Test
    void skuIdSegmentKeepsSemicolonContentUnderContextPath() throws Exception {
        when(service.add(anyString(), anyInt(), isNull())).thenReturn(new WriteResult.InvalidRequest());

        expectInvalidRequest(mvc.perform(post(URI.create("/app/inventory/ABC-1;lot=7")).contextPath("/app")
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)));
        verify(service).add("ABC-1;lot=7", 5, null);
    }

    // --- G4 and U3: body validation runs before the skuId check and the service ---

    @Test
    void purchaseInvalidBodyOnMissingSkuReturns400() throws Exception {
        when(service.purchase("missing", 0, null)).thenReturn(new StockOutcome.NotFound());

        expectInvalidRequest(mvc.perform(jsonPost(Post.PURCHASE, "missing", "{\"quantity\":0}")));
        verifyNoInteractions(service);
    }

    @Test
    void purchaseInvalidBodyAndInvalidSkuIdReturns400() throws Exception {
        expectInvalidRequest(mvc.perform(jsonPost(Post.PURCHASE, "-bad", "{\"quantity\":\"10\"}")));
        verifyNoInteractions(service);
    }

    @Test
    void createInvalidBodyAndInvalidSkuIdReturns400() throws Exception {
        expectInvalidRequest(mvc.perform(jsonPost(Post.CREATE, "-bad", "{}")));
        verifyNoInteractions(service);
    }

    // --- U2 and Y1 ---

    static Stream<Arguments> getIgnoresAcceptHeader() {
        List<Arguments> cases = new ArrayList<>();
        for (String accept : List.of("application/xml", "text/plain", "text/html", "image/png",
                "application/json;q=0")) {
            cases.add(Arguments.of("/inventory/" + SKU, accept, "{\"skuId\":\"widget\",\"quantity\":3}"));
            cases.add(Arguments.of("/inventory", accept, "[{\"skuId\":\"widget\",\"quantity\":3}]"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "GET {0} Accept {1}")
    @MethodSource
    void getIgnoresAcceptHeader(String path, String accept, String expectedJson) throws Exception {
        when(service.find(SKU)).thenReturn(Optional.of(new InventoryItem(SKU, 3)));
        when(service.list(null, null))
                .thenReturn(new InventoryPage(List.of(new InventoryItem(SKU, 3)), Optional.empty()));

        mvc.perform(get(path).header(ACCEPT, accept))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedJson, JsonCompareMode.STRICT));
    }

    @Test
    void getMissingSkuWithXmlAcceptReturns404TextPlain() throws Exception {
        when(service.find(SKU)).thenReturn(Optional.empty());

        expectText(mvc.perform(get("/inventory/{skuId}", SKU).accept(MediaType.APPLICATION_XML)),
                404, "SKU not found");
    }

    static Stream<Arguments> postWithUnacceptableAcceptReturns400() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            cases.add(Arguments.of(op, MediaType.APPLICATION_XML_VALUE));
            cases.add(Arguments.of(op, MediaType.TEXT_PLAIN_VALUE));
            // C3: q=0 on the most specific range matching JSON refuses it
            cases.add(Arguments.of(op, "application/json;q=0"));
            cases.add(Arguments.of(op, "application/json;q=0, */*"));
            cases.add(Arguments.of(op, "*/*;q=0"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} Accept {1}")
    @MethodSource
    void postWithUnacceptableAcceptReturns400(Post op, String accept) throws Exception {
        expectInvalidRequest(mvc.perform(post(op.template, SKU).header(ACCEPT, accept)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> postWithCompatibleAcceptSucceeds() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            cases.add(Arguments.of(op, "*/*"));
            cases.add(Arguments.of(op, "application/*"));
            cases.add(Arguments.of(op, null));
            // C3: a non-zero q, or a more specific range that accepts JSON, is fine
            cases.add(Arguments.of(op, "*/*;q=0.1"));
            cases.add(Arguments.of(op, "application/*;q=0, application/json"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} Accept {1}")
    @MethodSource
    void postWithCompatibleAcceptSucceeds(Post op, String accept) throws Exception {
        op.stubOk(service, SKU, 5);
        MockHttpServletRequestBuilder request = post(op.template, SKU)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY);
        if (accept != null) {
            request.header(ACCEPT, accept);
        }

        expectItem(mvc.perform(request), SKU, 5);
        op.verifyCalled(service, SKU, 5);
    }
}
