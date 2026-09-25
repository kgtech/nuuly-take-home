package com.kgtech.inventoryapi.inventory.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.StockOutcome;

/**
 * S12, AC1, AC2: one row per response in the original spec, asserting status, Content-Type and exact body. Every
 * request sends Accept: application/json; error rows must still answer text/plain (S5).
 */
@WebMvcTest(InventoryController.class)
class InventoryControllerContractTest {

    private static final String PURCHASE = "/inventory/widget/purchase";

    /** One spec response: the request, the service stub and the exact expected response. */
    record ContractRow(String name, HttpMethod method, String path, String body, Consumer<InventoryService> stub,
            int status, MediaType mediaType, String expectedBody) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static ContractRow json(String name, HttpMethod method, String path, String body,
            Consumer<InventoryService> stub, String expectedJson) {
        return new ContractRow(name, method, path, body, stub, 200, MediaType.APPLICATION_JSON, expectedJson);
    }

    private static ContractRow text(String name, HttpMethod method, String path, String body,
            Consumer<InventoryService> stub, int status, String expectedText) {
        return new ContractRow(name, method, path, body, stub, status, MediaType.TEXT_PLAIN, expectedText);
    }

    private static final Consumer<InventoryService> NO_STUB = service -> {
    };

    static Stream<ContractRow> specResponses() {
        return Stream.of(
                json("GET item 200", HttpMethod.GET, "/inventory/CW-XYCS-BM-01", null,
                        s -> when(s.find("CW-XYCS-BM-01"))
                                .thenReturn(Optional.of(new InventoryItem("CW-XYCS-BM-01", 10))),
                        "{\"skuId\":\"CW-XYCS-BM-01\",\"quantity\":10}"),
                text("GET item 404", HttpMethod.GET, "/inventory/widget", null,
                        s -> when(s.find("widget")).thenReturn(Optional.empty()),
                        404, "SKU not found"),
                json("POST create 200", HttpMethod.POST, "/inventory/widget", "{\"quantity\":10}",
                        s -> when(s.add("widget", 10, null)).thenReturn(new StockOutcome.Ok(10)),
                        "{\"skuId\":\"widget\",\"quantity\":10}"),
                text("POST create 400", HttpMethod.POST, "/inventory/widget", "{\"quantity\":0}", NO_STUB,
                        400, "Invalid request"),
                json("POST purchase 200", HttpMethod.POST, PURCHASE, "{\"quantity\":3}",
                        s -> when(s.purchase("widget", 3, null)).thenReturn(new StockOutcome.Ok(7)),
                        "{\"skuId\":\"widget\",\"quantity\":7}"),
                text("POST purchase 400 insufficient", HttpMethod.POST, PURCHASE, "{\"quantity\":3}",
                        s -> when(s.purchase("widget", 3, null)).thenReturn(new StockOutcome.Insufficient()),
                        400, "Insufficient inventory"),
                text("POST purchase 400 invalid", HttpMethod.POST, PURCHASE, "{\"quantity\":0}", NO_STUB,
                        400, "Invalid request"),
                text("POST purchase 404", HttpMethod.POST, PURCHASE, "{\"quantity\":3}",
                        s -> when(s.purchase("widget", 3, null)).thenReturn(new StockOutcome.NotFound()),
                        404, "SKU not found"),
                json("GET list 200", HttpMethod.GET, "/inventory", null,
                        s -> when(s.findAll())
                                .thenReturn(List.of(new InventoryItem("A", 1), new InventoryItem("b", 0))),
                        "[{\"skuId\":\"A\",\"quantity\":1},{\"skuId\":\"b\",\"quantity\":0}]"),
                json("GET list 200 empty", HttpMethod.GET, "/inventory", null,
                        s -> when(s.findAll()).thenReturn(List.of()),
                        "[]"));
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private static MockHttpServletRequestBuilder jsonRequest(HttpMethod method, String path, String body) {
        MockHttpServletRequestBuilder request = request(method, path).accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specResponses")
    void specResponse(ContractRow row) throws Exception {
        row.stub().accept(service);

        ResultActions result = mvc.perform(jsonRequest(row.method(), row.path(), row.body()))
                .andExpect(status().is(row.status()))
                .andExpect(content().contentTypeCompatibleWith(row.mediaType()));
        if (row.mediaType().equals(MediaType.APPLICATION_JSON)) {
            result.andExpect(content().json(row.expectedBody(), JsonCompareMode.STRICT));
        } else {
            result.andExpect(content().string(row.expectedBody()));
        }
    }

    /** U1: an add that would pass Long.MAX_VALUE answers 400 "Invalid request". */
    @Test
    void createOverflowReturns400InvalidRequest() throws Exception {
        when(service.add("widget", 5, null)).thenReturn(new StockOutcome.Overflow());

        mvc.perform(jsonRequest(HttpMethod.POST, "/inventory/widget", "{\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Invalid request"));
    }

    /** G1: the skuId reaches the service with its case unchanged. */
    @Test
    void createPassesSkuIdUnchanged() throws Exception {
        when(service.add("ABC", 5, null)).thenReturn(new StockOutcome.Ok(5));

        mvc.perform(post("/inventory/ABC").accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"skuId\":\"ABC\",\"quantity\":5}", JsonCompareMode.STRICT));

        verify(service).add("ABC", 5, null);
    }
}
