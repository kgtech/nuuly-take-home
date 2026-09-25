package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** U2, S6, OQ6: only GET /inventory and GET /inventory/** see Accept as application/json. Plain unit test. */
class JsonAcceptForGetFilterTest {

    private final JsonAcceptForGetFilter filter = new JsonAcceptForGetFilter();

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Accept", "application/xml");
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/inventory", "/inventory/x", "/inventory/x/purchase"})
    void rewritesAcceptOnInventoryGets(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", path), new MockHttpServletResponse(), chain);

        HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
        assertThat(downstream.getHeader("accept")).isEqualTo("application/json");
        assertThat(Collections.list(downstream.getHeaders("Accept"))).containsExactly("application/json");
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /v3/api-docs",
        "GET, /swagger-ui.html",
        "GET, /swagger-ui/index.html",
        "GET, /actuator/health",
        "GET, /inventoryx",
        "POST, /inventory/x"
    })
    void leavesOtherRequestsUntouched(String method, String path) throws Exception {
        MockHttpServletRequest request = request(method, path);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(((HttpServletRequest) chain.getRequest()).getHeader("Accept")).isEqualTo("application/xml");
    }
}
