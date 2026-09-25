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
 * G9, R4: GET /inventory passes limit and after to the service as raw strings, answers a bare JSON array and,
 * when the service returns a next cursor, one absolute {@code Link: <…>; rel="next"} built from the request.
 */
@WebMvcTest(InventoryController.class)
class InventoryListPagingTest {

    private static final Pattern LINK_VALUE = Pattern.compile("^<([^>]+)>; rel=\"next\"$");
    private static final List<InventoryItem> ITEMS = List.of(new InventoryItem("A", 1), new InventoryItem("B", 0));
    private static final String ITEMS_JSON = "[{\"skuId\":\"A\",\"quantity\":1},{\"skuId\":\"B\",\"quantity\":0}]";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private void stub(String limit, String after, Optional<Next> next) {
        when(service.list(limit, after)).thenReturn(new InventoryPage(ITEMS, next));
    }

    private static URI linkTarget(String link) {
        Matcher matcher = LINK_VALUE.matcher(link);
        assertThat(matcher.matches()).as(link).isTrue();
        return URI.create(matcher.group(1));
    }

    @Test
    void passesNoParamsAsNull() throws Exception {
        stub(null, null, Optional.empty());

        mvc.perform(get("/inventory")).andExpect(status().isOk());

        verify(service).list(null, null);
    }

    /** R4: limit is bound as a String, so a non-numeric value never fails type conversion; after is not validated. */
    @Test
    void passesRawLimitAndAfterToService() throws Exception {
        stub("abc", "x+y &z", Optional.empty());

        mvc.perform(get(URI.create("/inventory?limit=abc&after=x%2By%20%26z")))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT));

        verify(service).list("abc", "x+y &z");
    }

    @Test
    void passesEmptyAndNulValuesUnchanged() throws Exception {
        stub("", "a\0b", Optional.empty());

        mvc.perform(get(URI.create("/inventory?limit=&after=a%00b"))).andExpect(status().isOk());

        verify(service).list("", "a\0b");
    }

    @Test
    void linkHeaderFormat() throws Exception {
        stub("2", null, Optional.of(new Next(2, "B")));

        mvc.perform(get("/inventory?limit=2").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().stringValues(LINK, "<http://localhost/inventory?limit=2&after=B>; rel=\"next\""));
    }

    /** G9: the Link URL is absolute and reflects the request's scheme, host and port. */
    @Test
    void linkReflectsRequestHost() throws Exception {
        stub("2", "A", Optional.of(new Next(2, "B")));

        mvc.perform(get(URI.create("https://api.example.com:8443/inventory?limit=2&after=A")))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK,
                        "<https://api.example.com:8443/inventory?limit=2&after=B>; rel=\"next\""));
    }

    /** The cursor is encoded strictly, so reserved and non-ASCII characters survive the round trip. */
    @Test
    void linkEncodesAfter() throws Exception {
        String after = "a+b&c=d é/%#?";
        stub("2", null, Optional.of(new Next(2, after)));

        String link = mvc.perform(get("/inventory?limit=2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(LINK);

        assertThat(link).isEqualTo(
                "<http://localhost/inventory?limit=2&after=a%2Bb%26c%3Dd%20%C3%A9%2F%25%23%3F>; rel=\"next\"");
        URI target = linkTarget(link);
        MultiValueMap<String, String> params = UriComponentsBuilder.fromUri(target).build(true).getQueryParams();
        assertThat(params.keySet()).containsExactly("limit", "after");
        assertThat(UriUtils.decode(params.getFirst("after"), StandardCharsets.UTF_8)).isEqualTo(after);
    }

    /** R8: the Link carries the service's normalized limit, not the raw request value. */
    @Test
    void linkUsesNormalizedLimit() throws Exception {
        stub("+9999", null, Optional.of(new Next(250, "B")));

        mvc.perform(get(URI.create("/inventory?limit=%2B9999")))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/inventory?limit=250&after=B>; rel=\"next\""));
    }

    @Test
    void linkDropsOtherQueryParams() throws Exception {
        stub("2", "A", Optional.of(new Next(2, "B")));

        mvc.perform(get("/inventory?foo=bar&limit=2&after=A&baz"))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/inventory?limit=2&after=B>; rel=\"next\""));
    }

    @Test
    void noNextMeansNoLinkHeader() throws Exception {
        stub("2", "A", Optional.empty());

        mvc.perform(get("/inventory?limit=2&after=A"))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(LINK));
    }

    /** G9: the body stays the spec's bare array whether or not there is a next page. */
    @Test
    void emptyPageIsBareEmptyArray() throws Exception {
        when(service.list("2", "zzz")).thenReturn(new InventoryPage(List.of(), Optional.empty()));

        mvc.perform(get("/inventory?limit=2&after=zzz"))
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

    /** U2: a paged GET ignores Accept and still carries the Link. */
    @Test
    void responseIsJsonEvenWithXmlAccept() throws Exception {
        stub("2", null, Optional.of(new Next(2, "B")));

        mvc.perform(get("/inventory?limit=2").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().string(LINK, "<http://localhost/inventory?limit=2&after=B>; rel=\"next\""));
    }
}
