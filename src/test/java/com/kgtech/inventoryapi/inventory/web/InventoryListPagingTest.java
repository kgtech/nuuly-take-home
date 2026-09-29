package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.InventoryPage;
import com.kgtech.inventoryapi.inventory.InventoryPage.Next;
import com.kgtech.inventoryapi.inventory.InventoryService;

/**
 * G9, C2, H4: GET /inventory passes only after to the service as a raw string (limit and every other parameter are
 * ignored), answers a bare JSON array and, when the service returns a next cursor, one absolute
 * {@code Link: <…>; rel="next"} built from the request's scheme, host, port and context path plus the routed path
 * /inventory and carrying only after. The limit-based /v2 list is V2ListPagingTest's.
 */
@WebMvcTest(InventoryController.class)
@Import(OutcomeResponses.class)
class InventoryListPagingTest {

    private static final Pattern LINK_VALUE = Pattern.compile("^<([^>]+)>; rel=\"next\"$");
    private static final List<InventoryItem> ITEMS = List.of(new InventoryItem("A", 1), new InventoryItem("B", 0));
    private static final String ITEMS_JSON = "[{\"skuId\":\"A\",\"quantity\":1},{\"skuId\":\"B\",\"quantity\":0}]";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private void stub(String after, Optional<Next> next) {
        when(service.list(after)).thenReturn(new InventoryPage(ITEMS, next));
    }

    private static URI linkTarget(String link) {
        Matcher matcher = LINK_VALUE.matcher(link);
        assertThat(matcher.matches()).as(link).isTrue();
        return URI.create(matcher.group(1));
    }

    @Test
    void passesNoParamsAsNull() throws Exception {
        stub(null, Optional.empty());

        mvc.perform(get("/inventory")).andExpect(status().isOk());

        verify(service).list(null);
    }

    /** H4: after is passed raw and never validated; limit, in any form, and other parameters never reach the service. */
    @Test
    void passesOnlyAfterToTheService() throws Exception {
        stub("x+y &z", Optional.empty());

        mvc.perform(get(URI.create("/inventory?limit=abc&limit=3&foo=bar&after=x%2By%20%26z")))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT));

        verify(service).list("x+y &z");
    }

    @Test
    void passesEmptyAndNulValuesUnchanged() throws Exception {
        stub("a\0b", Optional.empty());

        mvc.perform(get(URI.create("/inventory?after=a%00b"))).andExpect(status().isOk());

        verify(service).list("a\0b");
    }

    @Test
    void linkHeaderFormat() throws Exception {
        stub(null, Optional.of(new Next(250, "B")));

        mvc.perform(get("/inventory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().stringValues(LINK, "<http://localhost/inventory?after=B>; rel=\"next\""));
    }

    /** G9: the Link URL is absolute and reflects the request's scheme, host and port. */
    @Test
    void linkReflectsRequestHost() throws Exception {
        stub("A", Optional.of(new Next(250, "B")));

        mvc.perform(get(URI.create("https://api.example.com:8443/inventory?after=A")))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<https://api.example.com:8443/inventory?after=B>; rel=\"next\""));
    }

    /** The cursor is encoded strictly, so reserved and non-ASCII characters survive the round trip. */
    @Test
    void linkEncodesAfter() throws Exception {
        String after = "a+b&c=d é/%#?";
        stub(null, Optional.of(new Next(250, after)));

        String link = mvc.perform(get("/inventory"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(LINK);

        assertThat(link).isEqualTo(
                "<http://localhost/inventory?after=a%2Bb%26c%3Dd%20%C3%A9%2F%25%23%3F>; rel=\"next\"");
        URI target = linkTarget(link);
        MultiValueMap<String, String> params = UriComponentsBuilder.fromUri(target).build(true).getQueryParams();
        assertThat(params.keySet()).containsExactly("after");
        assertThat(UriUtils.decode(params.getFirst("after"), StandardCharsets.UTF_8)).isEqualTo(after);
    }

    /** C2: path parameters on the request path are not echoed into the Link. */
    @Test
    void linkDropsPathParameters() throws Exception {
        stub(null, Optional.of(new Next(250, "B")));

        mvc.perform(get(URI.create("/inventory;x=1")))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/inventory?after=B>; rel=\"next\""));
    }

    /** C2: the Link keeps the request's context path. */
    @Test
    void linkKeepsContextPath() throws Exception {
        stub(null, Optional.of(new Next(250, "B")));

        mvc.perform(get("/app/inventory").contextPath("/app"))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/app/inventory?after=B>; rel=\"next\""));
    }

    /** OD-5, H4: the Link carries only after: not the request's limit, not the service's page size, not other params. */
    @Test
    void linkCarriesOnlyAfter() throws Exception {
        stub("A", Optional.of(new Next(250, "B")));

        mvc.perform(get("/inventory?foo=bar&limit=2&after=A&baz"))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/inventory?after=B>; rel=\"next\""));
    }

    @Test
    void noNextMeansNoLinkHeader() throws Exception {
        stub("A", Optional.empty());

        mvc.perform(get("/inventory?after=A"))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(LINK));
    }

    /** G9: the body stays the spec's bare array whether or not there is a next page. */
    @Test
    void emptyPageIsBareEmptyArray() throws Exception {
        when(service.list("zzz")).thenReturn(new InventoryPage(List.of(), Optional.empty()));

        mvc.perform(get("/inventory?after=zzz"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("[]"))
                .andExpect(header().doesNotExist(LINK));
    }

    /** Z3, S5: a repeated after is 400 text/plain "Invalid request" and the service is never called. */
    @ParameterizedTest(name = "?{0} → 400")
    @ValueSource(strings = {"after=A-1&after=B-2", "limit=2&after=A-1&after=B-2", "after=&after=B-2"})
    void repeatedAfterReturns400WithoutCallingService(String query) throws Exception {
        mvc.perform(get(URI.create("/inventory?" + query)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Invalid request"))
                .andExpect(header().doesNotExist(LINK));

        verifyNoInteractions(service);
    }

    /** R4, Z3: one after value containing a comma is passed through whole, neither split nor rejected. */
    @ParameterizedTest(name = "?{0}")
    @ValueSource(strings = {"after=A-1%2CB-2", "after=A-1,B-2"})
    void singleAfterWithCommaIsPassedWhole(String query) throws Exception {
        stub("A-1,B-2", Optional.empty());

        mvc.perform(get(URI.create("/inventory?" + query)))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT));

        verify(service).list("A-1,B-2");
    }

    /** U2: a paged GET ignores Accept and still carries the Link. */
    @Test
    void responseIsJsonEvenWithXmlAccept() throws Exception {
        stub(null, Optional.of(new Next(250, "B")));

        mvc.perform(get("/inventory").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().string(LINK, "<http://localhost/inventory?after=B>; rel=\"next\""));
    }
}
