package com.kgtech.inventoryapi.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.kgtech.inventoryapi.idempotency.KeyedResult;
import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * U3, S3, G8: the controller's check order with an Idempotency-Key — @Valid body → key format → skuId pattern →
 * service — and how a KeyedResult becomes the response. The service is a mock, so no database is involved.
 */
@WebMvcTest(InventoryController.class)
class IdempotencyHeaderOrderTest {

    private static final String CREATE = "/inventory/widget";
    private static final String PURCHASE = "/inventory/widget/purchase";
    private static final String KEY = "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private ResultActions send(String path, String body, String... keys) throws Exception {
        var request = post(path)
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

    @ParameterizedTest
    @ValueSource(strings = {CREATE, PURCHASE, "/inventory/-bad", "/inventory/-bad/purchase"})
    void badBodyWinsOverBadKey(String path) throws Exception {
        expectText(send(path, "{\"quantity\":0}", "nope"), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    @Test
    void badKeyWinsOverBadSkuIdOnPurchase() throws Exception {
        expectText(send("/inventory/-bad/purchase", "{\"quantity\":1}", "nope"), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    @Test
    void validKeyBadSkuIdOnPurchase404WithoutService() throws Exception {
        expectText(send("/inventory/-bad/purchase", "{\"quantity\":1}", KEY), 404, "SKU not found");

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {CREATE, "/inventory/-bad"})
    void badKeyOnCreate400WithoutService(String path) throws Exception {
        expectText(send(path, "{\"quantity\":1}", "nope"), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    @Test
    void validKeyBadSkuIdOnCreate400WithoutService() throws Exception {
        expectText(send("/inventory/-bad", "{\"quantity\":1}", KEY), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {CREATE, PURCHASE})
    void emptyKey400(String path) throws Exception {
        expectText(send(path, "{\"quantity\":1}", ""), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    /** Two header values reach the controller comma-joined and fail the format check. */
    @ParameterizedTest
    @ValueSource(strings = {CREATE, PURCHASE})
    void duplicateKeyHeaders400(String path) throws Exception {
        expectText(send(path, "{\"quantity\":1}", KEY, UUID.randomUUID().toString()), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    @Test
    void validKeyPassesUuidToKeyedOverloadOnCreate() throws Exception {
        String body = "{\"skuId\":\"widget\",\"quantity\":5}";
        when(service.add("widget", 5, UUID.fromString(KEY)))
                .thenReturn(new KeyedResult.Executed(new StoredResponse(200, "application/json", body)));

        send(CREATE, "{\"quantity\":5}", KEY.toUpperCase())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(body));

        verify(service).add("widget", 5, UUID.fromString(KEY));
        verify(service, never()).add(anyString(), anyInt());
    }

    @Test
    void validKeyPassesUuidToKeyedOverloadOnPurchase() throws Exception {
        String body = "{\"skuId\":\"widget\",\"quantity\":7}";
        when(service.purchase("widget", 3, UUID.fromString(KEY)))
                .thenReturn(new KeyedResult.Executed(new StoredResponse(200, "application/json", body)));

        send(PURCHASE, "{\"quantity\":3}", KEY)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(body));

        verify(service).purchase("widget", 3, UUID.fromString(KEY));
        verify(service, never()).purchase(anyString(), anyInt());
    }

    /** G8: without the header the unkeyed method runs, exactly as before. */
    @Test
    void absentKeyUsesUnkeyedMethod() throws Exception {
        when(service.add("widget", 5)).thenReturn(new StockOutcome.Ok(5));
        when(service.purchase("widget", 3)).thenReturn(new StockOutcome.Ok(2));

        send(CREATE, "{\"quantity\":5}").andExpect(status().isOk());
        send(PURCHASE, "{\"quantity\":3}").andExpect(status().isOk());

        verify(service).add("widget", 5);
        verify(service).purchase("widget", 3);
        verify(service, never()).add(anyString(), anyInt(), any(UUID.class));
        verify(service, never()).purchase(anyString(), anyInt(), any(UUID.class));
    }

    static Stream<Arguments> replayedResultRenderedUnchanged() {
        return Stream.of(
                Arguments.of(new StoredResponse(200, "application/json", "{\"skuId\":\"widget\",\"quantity\":5}")),
                Arguments.of(new StoredResponse(404, "text/plain", "SKU not found")),
                Arguments.of(new StoredResponse(400, "text/plain", "Insufficient inventory")),
                Arguments.of(new StoredResponse(400, "text/plain", "Invalid request")));
    }

    /** Y4: a replay sends the stored status, Content-Type and body unchanged. */
    @ParameterizedTest
    @MethodSource
    void replayedResultRenderedUnchanged(StoredResponse stored) throws Exception {
        when(service.purchase("widget", 5, UUID.fromString(KEY))).thenReturn(new KeyedResult.Replayed(stored));

        send(PURCHASE, "{\"quantity\":5}", KEY)
                .andExpect(status().is(stored.status()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType(stored.contentType())))
                .andExpect(content().string(stored.body()));
    }

    @Test
    void executedAndReplayedRenderIdentically() throws Exception {
        StoredResponse stored = new StoredResponse(200, "application/json", "{\"skuId\":\"widget\",\"quantity\":5}");
        when(service.add("widget", 5, UUID.fromString(KEY)))
                .thenReturn(new KeyedResult.Executed(stored), new KeyedResult.Replayed(stored));

        String first = send(CREATE, "{\"quantity\":5}", KEY).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Type");
        send(CREATE, "{\"quantity\":5}", KEY)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", first))
                .andExpect(content().string(stored.body()));
    }

    @ParameterizedTest
    @ValueSource(strings = {CREATE, PURCHASE})
    void rejectedResultIs400TextPlain(String path) throws Exception {
        when(service.add("widget", 1, UUID.fromString(KEY))).thenReturn(new KeyedResult.Rejected());
        when(service.purchase("widget", 1, UUID.fromString(KEY))).thenReturn(new KeyedResult.Rejected());

        expectText(send(path, "{\"quantity\":1}", KEY), 400, "Invalid request");
    }
}
