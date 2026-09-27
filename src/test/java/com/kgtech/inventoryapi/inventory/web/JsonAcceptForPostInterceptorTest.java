package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

/**
 * C3, U2, Y1: on POST the most specific Accept range that matches application/json decides (RFC 9110 §12.5.1), and
 * q=0 refuses JSON with the HttpMediaTypeNotAcceptableException the advice maps to 400. GET is never checked (the
 * filter already ignores Accept). Plain unit test; one Accept header line per value in {@code accept}.
 */
class JsonAcceptForPostInterceptorTest {

    private final JsonAcceptForPostInterceptor interceptor = new JsonAcceptForPostInterceptor();

    private static MockHttpServletRequest request(String method, List<String> accept) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/inventory/widget");
        for (String line : accept) {
            request.addHeader(ACCEPT, line);
        }
        return request;
    }

    static Stream<Arguments> postAcceptDecides() {
        return Stream.of(
                // refused: the most specific range matching JSON has q=0
                Arguments.of(List.of("application/json;q=0"), true),
                Arguments.of(List.of("application/json;q=0.0"), true),
                Arguments.of(List.of("application/json;q=0, */*"), true),
                Arguments.of(List.of("*/*;q=0"), true),
                Arguments.of(List.of("application/*;q=0"), true),
                Arguments.of(List.of("text/plain, application/json;q=0"), true),
                Arguments.of(List.of("application/json;charset=utf-8;q=0"), true),
                Arguments.of(List.of("application/json;q=0", "text/html"), true),
                Arguments.of(List.of("application/*+json;q=0"), true),
                // accepted
                Arguments.of(List.of(), false),
                Arguments.of(List.of("application/json"), false),
                Arguments.of(List.of("*/*;q=0.1"), false),
                Arguments.of(List.of("application/json;q=0.001"), false),
                Arguments.of(List.of("application/*;q=0, application/json"), false),
                Arguments.of(List.of("*/*;q=0, application/json"), false),
                // equally specific ranges: the highest q decides
                Arguments.of(List.of("application/json, application/json;q=0"), false));
    }

    @ParameterizedTest(name = "POST Accept {0} → refused {1}")
    @MethodSource
    void postAcceptDecides(List<String> accept, boolean refused) throws Exception {
        MockHttpServletRequest request = request("POST", accept);
        MockHttpServletResponse response = new MockHttpServletResponse();

        if (refused) {
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .isInstanceOfSatisfying(HttpMediaTypeNotAcceptableException.class,
                            e -> assertThat(e.getSupportedMediaTypes()).containsExactly(APPLICATION_JSON));
        } else {
            assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        }
        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** U2: GET ignores Accept, so even q=0 on every range passes. */
    @Test
    void getIsNeverChecked() throws Exception {
        MockHttpServletRequest request = request("GET", List.of("application/json;q=0", "*/*;q=0"));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
