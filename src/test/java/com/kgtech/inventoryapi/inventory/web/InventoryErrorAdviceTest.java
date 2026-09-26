package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.ALLOW;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import org.apache.tomcat.util.http.InvalidParameterException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.kgtech.inventoryapi.inventory.InventoryService;

/**
 * D6, S5, S6, G6, T3, G10, AC5, AC8: every error the advice maps is text/plain with G6's fixed text or the standard
 * reason phrase, whatever the Accept header. Every request sends Accept: application/json unless noted.
 */
@WebMvcTest(InventoryController.class)
@ExtendWith(OutputCaptureExtension.class)
class InventoryErrorAdviceTest {

    /** The four spec operations, each with a way to make the mocked service throw. */
    enum Operation {
        GET_ITEM(HttpMethod.GET, "/inventory/widget", null),
        LIST(HttpMethod.GET, "/inventory", null),
        CREATE(HttpMethod.POST, "/inventory/widget", "{\"quantity\":5}"),
        PURCHASE(HttpMethod.POST, "/inventory/widget/purchase", "{\"quantity\":5}");

        final HttpMethod method;
        final String path;
        final String body;

        Operation(HttpMethod method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        MockHttpServletRequestBuilder request() {
            // qualified: this method's name hides the static import
            MockHttpServletRequestBuilder request =
                    MockMvcRequestBuilders.request(method, path).accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            return request;
        }

        void failWith(InventoryService service, RuntimeException failure) {
            switch (this) {
                case GET_ITEM -> when(service.find(anyString())).thenThrow(failure);
                case LIST -> when(service.list(any(), any())).thenThrow(failure);
                case CREATE -> when(service.add(anyString(), anyInt(), any())).thenThrow(failure);
                case PURCHASE -> when(service.purchase(anyString(), anyInt(), any())).thenThrow(failure);
            }
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private static ResultActions expectText(ResultActions result, int status, String body) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }

    /** T3: 405 keeps the Allow header and uses the standard reason phrase. */
    @Test
    void deleteSkuReturns405WithAllowHeader() throws Exception {
        expectText(mvc.perform(delete("/inventory/x").accept(MediaType.APPLICATION_JSON)), 405, "Method Not Allowed")
                .andExpect(header().string(ALLOW, allOf(containsString("GET"), containsString("POST"))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "PUT, /inventory",
        "DELETE, /inventory/x/purchase",
        "GET, /inventory/x/purchase"
    })
    void otherMethodsReturn405(String method, String path) throws Exception {
        expectText(mvc.perform(request(HttpMethod.valueOf(method), path).accept(MediaType.APPLICATION_JSON)),
                405, "Method Not Allowed")
                .andExpect(header().exists(ALLOW));
    }

    @ParameterizedTest
    @CsvSource({"/nope", "/inventory/a/b"})
    void unknownPathReturns404NotFound(String path) throws Exception {
        expectText(mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)), 404, "Not Found");
    }

    /**
     * S6, C1: errors on /actuator/** and the springdoc paths are left to Spring (the advice rethrows), so no
     * text/plain body is written; MockMvc does not dispatch /error, so the body stays empty. Real-server behaviour is
     * in LibraryPathErrorsIntegrationTest.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/nope", "/actuator", "/v3/api-docs/nope", "/v3/api-docs.yaml",
        "/swagger-ui.html", "/swagger-ui/nope.js"})
    void libraryPathErrorsAreLeftToSpring(String path) throws Exception {
        mvc.perform(get(path).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    /** S6, C1: paths that only look like library paths keep the text/plain contract. */
    @ParameterizedTest
    @ValueSource(strings = {"/actuatorx", "/v3/api-docsx", "/v3/api-docs.yaml/x", "/swagger-uix",
        "/swagger-ui.htmlx"})
    void lookalikePathsKeepTextPlain(String path) throws Exception {
        expectText(mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)), 404, "Not Found");
    }

    /** AC8, S6: the message of an unexpected exception never reaches the client. */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void forcedRuntimeExceptionReturns500(Operation operation) throws Exception {
        operation.failWith(service, new RuntimeException("boom"));

        expectText(mvc.perform(operation.request()), 500, "Internal server error")
                .andExpect(content().string(not(containsString("boom"))));
    }

    /** S6: the catch-all logs the stack trace at ERROR. */
    @Test
    void unhandledExceptionIsLoggedAtError(CapturedOutput output) throws Exception {
        Operation.GET_ITEM.failWith(service, new RuntimeException("boom-logged"));

        expectText(mvc.perform(Operation.GET_ITEM.request()), 500, "Internal server error");

        assertThat(output.getAll().lines())
                .as(output.getAll())
                .anyMatch(line -> line.contains("ERROR") && line.contains("Unhandled exception on GET /inventory/widget"));
        assertThat(output.getAll()).contains("java.lang.RuntimeException: boom-logged");
    }

    /** PR #10 follow-up, W2: a serialization failure that escapes the retries is a plain 500. */
    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"CREATE", "PURCHASE"})
    void serializationFailureReturns500(Operation operation) throws Exception {
        operation.failWith(service, new PessimisticLockingFailureException("could not serialize access",
                new SQLException("could not serialize access", "40001")));

        expectText(mvc.perform(operation.request()), 500, "Internal server error");
    }

    /**
     * Z3, S5: Tomcat throws InvalidParameterException when the query can't be decoded (it surfaces from the first
     * parameter read); the advice answers 400 "Invalid request", not the catch-all's 500. The undecodable query
     * itself is covered through real Tomcat in InventoryListMalformedQueryIntegrationTest.
     */
    @Test
    void invalidParameterExceptionOnListReturns400() throws Exception {
        Operation.LIST.failWith(service, new InvalidParameterException("Character decoding failed", 400));

        expectText(mvc.perform(Operation.LIST.request()), 400, "Invalid request");
    }

    /** D6: ProblemDetail stays off, even when the client asks for it. */
    @Test
    void problemDetailIsOff() throws Exception {
        expectText(mvc.perform(delete("/inventory/x").accept(MediaType.APPLICATION_PROBLEM_JSON)),
                405, "Method Not Allowed");
    }
}
