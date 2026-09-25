package com.kgtech.inventoryapi.inventory;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request validation at the HTTP edge: G13/G3 bodies (AC3), G11/S2 skuIds, G4/U3 ordering, U2/Y1 Accept handling.
 * Every 400 is text/plain "Invalid request" and never reaches the service.
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
                when(service.add(skuId, quantity)).thenReturn(new StockOutcome.Ok(quantity));
            } else {
                when(service.purchase(skuId, quantity)).thenReturn(new StockOutcome.Ok(quantity));
            }
        }

        void verifyCalled(InventoryService service, String skuId, int quantity) {
            if (this == CREATE) {
                verify(service).add(skuId, quantity);
            } else {
                verify(service).purchase(skuId, quantity);
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
    void createWithInvalidSkuIdReturns400(String skuId) throws Exception {
        expectInvalidRequest(mvc.perform(jsonPost(Post.CREATE, skuId, VALID_BODY)));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("invalidSkuIds")
    void getWithInvalidSkuIdReturns404(String skuId) throws Exception {
        expectText(mvc.perform(get("/inventory/{skuId}", skuId).accept(MediaType.APPLICATION_JSON)),
                404, "SKU not found");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("invalidSkuIds")
    void purchaseWithInvalidSkuIdReturns404(String skuId) throws Exception {
        expectText(mvc.perform(jsonPost(Post.PURCHASE, skuId, VALID_BODY)), 404, "SKU not found");
        verifyNoInteractions(service);
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

    // --- G4 and U3: body validation runs before the skuId check and the service ---

    @Test
    void purchaseInvalidBodyOnMissingSkuReturns400() throws Exception {
        when(service.purchase("missing", 0)).thenReturn(new StockOutcome.NotFound());

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
        for (String accept : List.of("application/xml", "text/plain", "text/html", "image/png")) {
            cases.add(Arguments.of("/inventory/" + SKU, accept, "{\"skuId\":\"widget\",\"quantity\":3}"));
            cases.add(Arguments.of("/inventory", accept, "[{\"skuId\":\"widget\",\"quantity\":3}]"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "GET {0} Accept {1}")
    @MethodSource
    void getIgnoresAcceptHeader(String path, String accept, String expectedJson) throws Exception {
        when(service.find(SKU)).thenReturn(Optional.of(new InventoryItem(SKU, 3)));
        when(service.findAll()).thenReturn(List.of(new InventoryItem(SKU, 3)));

        mvc.perform(get(path).header("Accept", accept))
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
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} Accept {1}")
    @MethodSource
    void postWithUnacceptableAcceptReturns400(Post op, String accept) throws Exception {
        expectInvalidRequest(mvc.perform(post(op.template, SKU).header("Accept", accept)
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> postWithCompatibleAcceptSucceeds() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            cases.add(Arguments.of(op, "*/*"));
            cases.add(Arguments.of(op, "application/*"));
            cases.add(Arguments.of(op, null));
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
            request.header("Accept", accept);
        }

        expectItem(mvc.perform(request), SKU, 5);
        op.verifyCalled(service, SKU, 5);
    }
}
