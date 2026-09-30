package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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

import org.junit.jupiter.api.Test;
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

import com.kgtech.inventoryapi.inventory.InventoryPage.Next;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.SkuItem;
import com.kgtech.inventoryapi.inventory.SkuPage;

/**
 * G9, R4, R8, C2, H5, moved from the unversioned list (F-07): GET /v2/inventory passes limit and after to the service
 * as raw strings and builds the Link from the routed path /v2/inventory carrying the service's normalized limit and
 * the last skuId. Host, context path and path-parameter handling are the shared Link builder's, tested on the
 * unversioned list (InventoryListPagingTest). The service is a mock.
 */
@WebMvcTest(SkuDetailsController.class)
@Import(OutcomeResponses.class)
class V2ListPagingTest {

    private static final List<SkuItem> ITEMS = List.of(new SkuItem("A", 1, Optional.empty(), 0),
            new SkuItem("B", 0, Optional.empty(), 0));
    private static final String ITEMS_JSON = "[{\"skuId\":\"A\",\"quantity\":1},{\"skuId\":\"B\",\"quantity\":0}]";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InventoryService service;

    private void stub(String limit, String after, Optional<Next> next) {
        when(service.listSkus(limit, after)).thenReturn(new SkuPage(ITEMS, next));
    }

    /** R4: limit is bound as a String, so a non-numeric value never fails type conversion; after is not validated. */
    @Test
    void passesRawLimitAndAfterToService() throws Exception {
        stub("abc", "x+y &z", Optional.empty());

        mvc.perform(get(URI.create("/v2/inventory?limit=abc&after=x%2By%20%26z")))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT));

        verify(service).listSkus("abc", "x+y &z");
    }

    @Test
    void passesNoParamsEmptyAndNulValuesUnchanged() throws Exception {
        stub(null, null, Optional.empty());
        stub("", "a\0b", Optional.empty());

        mvc.perform(get("/v2/inventory")).andExpect(status().isOk());
        mvc.perform(get(URI.create("/v2/inventory?limit=&after=a%00b"))).andExpect(status().isOk());

        verify(service).listSkus(null, null);
        verify(service).listSkus("", "a\0b");
    }

    @Test
    void linkHeaderCarriesLimitAndAfter() throws Exception {
        stub("2", null, Optional.of(new Next(2, "B")));

        mvc.perform(get("/v2/inventory?limit=2").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(ITEMS_JSON, JsonCompareMode.STRICT))
                .andExpect(header().stringValues(LINK, "<http://localhost/v2/inventory?limit=2&after=B>; rel=\"next\""));
    }

    /** R8: the Link carries the service's normalized limit, not the raw request value. */
    @Test
    void linkUsesNormalizedLimit() throws Exception {
        stub("+9999", null, Optional.of(new Next(250, "B")));

        mvc.perform(get(URI.create("/v2/inventory?limit=%2B9999")))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/v2/inventory?limit=250&after=B>; rel=\"next\""));
    }

    @Test
    void linkDropsOtherQueryParams() throws Exception {
        stub("2", "A", Optional.of(new Next(2, "B")));

        mvc.perform(get("/v2/inventory?foo=bar&limit=2&after=A&baz"))
                .andExpect(status().isOk())
                .andExpect(header().string(LINK, "<http://localhost/v2/inventory?limit=2&after=B>; rel=\"next\""));
    }

    /** The cursor is encoded strictly, so reserved and non-ASCII characters survive the round trip. */
    @Test
    void linkEncodesAfter() throws Exception {
        String after = "a+b&c=d é/%#?";
        stub("2", null, Optional.of(new Next(2, after)));

        String link = mvc.perform(get("/v2/inventory?limit=2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(LINK);

        assertThat(link).isEqualTo(
                "<http://localhost/v2/inventory?limit=2&after=a%2Bb%26c%3Dd%20%C3%A9%2F%25%23%3F>; rel=\"next\"");
        URI target = URI.create(link.substring(1, link.indexOf('>')));
        MultiValueMap<String, String> params = UriComponentsBuilder.fromUri(target).build(true).getQueryParams();
        assertThat(params.keySet()).containsExactly("limit", "after");
        assertThat(UriUtils.decode(params.getFirst("after"), StandardCharsets.UTF_8)).isEqualTo(after);
    }

    @Test
    void noNextMeansNoLinkHeader() throws Exception {
        stub("2", "A", Optional.empty());

        mvc.perform(get("/v2/inventory?limit=2&after=A"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(LINK));
    }
}
