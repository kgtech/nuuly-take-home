package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.WriteResult;

/**
 * H3, U3 at the controller: on the unversioned POSTs @Valid runs first, then a present Idempotency-Key (any value,
 * including an empty one) is 400 before the service is called, then the raw skuId goes to the key-less service
 * methods. What the service and the database do is UnversionedIdempotencyIntegrationTest's and the service tests'.
 * The service is a mock, so no database is involved.
 */
@WebMvcTest(InventoryController.class)
@Import(OutcomeResponses.class)
class UnversionedKeyOrderTest {

    private static final String KEY = "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b";

    /** The two POST operations. */
    enum Post {
        CREATE("/inventory/{skuId}"),
        PURCHASE("/inventory/{skuId}/purchase");

        final String path;

        Post(String path) {
            this.path = path;
        }

        void stub(InventoryService service, String skuId, int quantity, WriteResult result) {
            if (this == CREATE) {
                when(service.add(skuId, quantity)).thenReturn(result);
            } else {
                when(service.purchase(skuId, quantity)).thenReturn(result);
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

    private ResultActions send(Post op, String skuId, String body, String... keyLines) throws Exception {
        var request = post(op.path, skuId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (keyLines.length > 0) {
            request.header(IDEMPOTENCY_KEY, (Object[]) keyLines);
        }
        return mvc.perform(request);
    }

    private static void expectText(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }

    /** G4, U3: a bad body is 400 whatever the key and the skuId say, and nothing reaches the service. */
    @ParameterizedTest
    @EnumSource(Post.class)
    void aBadBodyIs400BeforeAnythingElse(Post op) throws Exception {
        expectText(send(op, "-bad", "{\"quantity\":0}", "nope"), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    /**
     * H3: a present key is 400 and the service is never called. The key values, the skuId order and the database
     * effects are UnversionedIdempotencyIntegrationTest's matrix.
     */
    @ParameterizedTest
    @EnumSource(Post.class)
    void aPresentKeyIs400AndTheServiceIsNeverCalled(Post op) throws Exception {
        expectText(send(op, "widget", "{\"quantity\":1}", KEY), 400, "Invalid request");

        verifyNoInteractions(service);
    }

    /** Without the header the raw skuId goes to the key-less service method, and its answer is rendered (G11). */
    @Test
    void withoutTheHeaderTheServiceAnswers() throws Exception {
        Post.CREATE.stub(service, "widget", 5, new StockOutcome.Ok(5));
        Post.PURCHASE.stub(service, "-bad", 1, new StockOutcome.NotFound());

        send(Post.CREATE, "widget", "{\"quantity\":5}").andExpect(status().isOk())
                .andExpect(content().string("{\"skuId\":\"widget\",\"quantity\":5}"));
        expectText(send(Post.PURCHASE, "-bad", "{\"quantity\":1}"), 404, "SKU not found");

        Post.CREATE.verifyCalled(service, "widget", 5);
        Post.PURCHASE.verifyCalled(service, "-bad", 1);
    }
}
