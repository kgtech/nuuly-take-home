package com.kgtech.inventoryapi.inventory;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * U3, S3, G8, Z1 at the controller: @Valid runs first; after that the controller passes the raw skuId and the raw
 * Idempotency-Key header (null when absent) to the service and renders the WriteResult it gets back. Key and skuId
 * checks live in the @Idempotent interceptor and the service (IdempotencyInterceptorTest, InventoryServiceReadTest,
 * IdempotencyApiIntegrationTest). The service is a mock, so no advice and no database are involved.
 */
@WebMvcTest(InventoryController.class)
class IdempotencyHeaderOrderTest {

    private static final String KEY = "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b";
    private static final String OTHER_KEY = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    /** The two POST operations. */
    enum Post {
        CREATE("/inventory/{skuId}"),
        PURCHASE("/inventory/{skuId}/purchase");

        final String path;

        Post(String path) {
            this.path = path;
        }

        void stub(InventoryService service, String skuId, int quantity, String key, WriteResult result) {
            if (this == CREATE) {
                when(service.add(skuId, quantity, key)).thenReturn(result);
            } else {
                when(service.purchase(skuId, quantity, key)).thenReturn(result);
            }
        }

        void verifyCalled(InventoryService service, String skuId, int quantity, String key) {
            if (this == CREATE) {
                verify(service).add(skuId, quantity, key);
            } else {
                verify(service).purchase(skuId, quantity, key);
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private ResultActions send(Post op, String skuId, String body, String... keys) throws Exception {
        var request = post(op.path, skuId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (keys.length > 0) {
            request.header("Idempotency-Key", (Object[]) keys);
        }
        return mvc.perform(request);
    }

    private static void expectText(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }

    static Stream<Arguments> badBodyWinsOverBadKey() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            for (String skuId : List.of("widget", "-bad")) {
                cases.add(Arguments.of(op, skuId));
            }
        }
        return cases.stream();
    }

    /** G4, U3: body validation runs before anything sees the key or the skuId. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void badBodyWinsOverBadKey(Post op, String skuId) throws Exception {
        expectText(send(op, skuId, "{\"quantity\":0}", "nope"), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    static Stream<Arguments> rawKeyAndSkuIdPassedThrough() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            for (String skuId : List.of("widget", "-bad", "ABC")) {
                for (String key : List.of(KEY, KEY.toUpperCase(), "nope", "", "1-1-1-1-1")) {
                    cases.add(Arguments.of(op, skuId, key));
                }
            }
        }
        return cases.stream();
    }

    /** S2, S3, Z1: the controller neither checks nor parses the key or the skuId; the service gets them unchanged. */
    @ParameterizedTest(name = "{0} {1} key \"{2}\"")
    @MethodSource
    void rawKeyAndSkuIdPassedThrough(Post op, String skuId, String key) throws Exception {
        String body = "{\"skuId\":\"" + skuId + "\",\"quantity\":1}";
        op.stub(service, skuId, 1, key, new WriteResult.Stored(new StoredResponse(200, "application/json", body)));

        send(op, skuId, "{\"quantity\":1}", key)
                .andExpect(status().isOk())
                .andExpect(content().string(body));

        op.verifyCalled(service, skuId, 1, key);
    }

    /** Two header values reach the service as one comma-joined string, which the interceptor rejects. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void duplicateKeyHeadersPassedThroughJoined(Post op) throws Exception {
        String joined = KEY + "," + OTHER_KEY;
        op.stub(service, "widget", 1, joined, new WriteResult.InvalidRequest());

        expectText(send(op, "widget", "{\"quantity\":1}", KEY, OTHER_KEY), 400, "Invalid request");

        op.verifyCalled(service, "widget", 1, joined);
    }

    /** G8: without the header the service gets a null key. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void absentHeaderPassesNull(Post op) throws Exception {
        op.stub(service, "widget", 5, null, new StockOutcome.Ok(5));

        send(op, "widget", "{\"quantity\":5}")
                .andExpect(status().isOk())
                .andExpect(content().string("{\"skuId\":\"widget\",\"quantity\":5}"));

        op.verifyCalled(service, "widget", 5, null);
    }

    static Stream<Arguments> storedResultRenderedUnchanged() {
        List<Arguments> cases = new ArrayList<>();
        for (Post op : Post.values()) {
            for (StoredResponse stored : List.of(
                    new StoredResponse(200, "application/json", "{\"skuId\":\"widget\",\"quantity\":5}"),
                    new StoredResponse(404, "text/plain", "SKU not found"),
                    new StoredResponse(400, "text/plain", "Insufficient inventory"),
                    new StoredResponse(400, "text/plain", "Invalid request"))) {
                cases.add(Arguments.of(op, stored));
            }
        }
        return cases.stream();
    }

    /** Y4: a stored response (first keyed response or replay) is sent with its status, Content-Type and body. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void storedResultRenderedUnchanged(Post op, StoredResponse stored) throws Exception {
        op.stub(service, "widget", 5, KEY, new WriteResult.Stored(stored));

        send(op, "widget", "{\"quantity\":5}", KEY)
                .andExpect(status().is(stored.status()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType(stored.contentType())))
                .andExpect(content().string(stored.body()));
    }

    /** Y4: a stored 200 has the same Content-Type header as the unkeyed 200. */
    @Test
    void stored200HasSameContentTypeAsUnkeyed200() throws Exception {
        String body = "{\"skuId\":\"widget\",\"quantity\":5}";
        Post.CREATE.stub(service, "widget", 5, null, new StockOutcome.Ok(5));
        Post.CREATE.stub(service, "widget", 5, KEY,
                new WriteResult.Stored(new StoredResponse(200, "application/json", body)));

        String unkeyed = send(Post.CREATE, "widget", "{\"quantity\":5}").andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Type");
        send(Post.CREATE, "widget", "{\"quantity\":5}", KEY)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", unkeyed))
                .andExpect(content().string(body));
    }

    /** S3, S8, T1: the InvalidRequest outcome is 400 text/plain "Invalid request". */
    @ParameterizedTest
    @EnumSource(Post.class)
    void invalidRequestResultIs400TextPlain(Post op) throws Exception {
        op.stub(service, "widget", 1, KEY, new WriteResult.InvalidRequest());

        expectText(send(op, "widget", "{\"quantity\":1}", KEY), 400, "Invalid request");
    }
}
