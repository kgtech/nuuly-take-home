package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * OQ2, S6, S12: springdoc documents exactly the spec's operations, codes and media types, and its own paths keep
 * library behaviour. Same annotations as InventoryApiIntegrationTest so the context and container are reused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsTest {

    @Autowired
    MockMvc mvc;

    private String apiDocs() throws Exception {
        return mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Map<String, Map<String, Map<String, Object>>> operations() throws Exception {
        return JsonPath.read(apiDocs(), "$.paths");
    }

    @Test
    void apiDocsAreServedAsJson() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void specPathsAreExactlyTheFour() throws Exception {
        Map<String, Map<String, Map<String, Object>>> paths = operations();

        assertThat(paths.keySet())
                .containsExactlyInAnyOrder("/inventory", "/inventory/{skuId}", "/inventory/{skuId}/purchase");
        assertThat(paths.get("/inventory").keySet()).containsExactly("get");
        assertThat(paths.get("/inventory/{skuId}").keySet()).containsExactlyInAnyOrder("get", "post");
        assertThat(paths.get("/inventory/{skuId}/purchase").keySet()).containsExactly("post");
    }

    @ParameterizedTest(name = "{1} {0} → {2}")
    @CsvSource(delimiter = '|', value = {
        "/inventory/{skuId}          | get  | 200,404",
        "/inventory/{skuId}          | post | 200,400",
        "/inventory/{skuId}/purchase | post | 200,400,404",
        "/inventory                  | get  | 200"
    })
    void eachOperationListsExactlyItsSpecCodes(String path, String method, String codes) throws Exception {
        Map<String, Object> responses = JsonPath.read(apiDocs(), "$.paths['" + path + "']." + method + ".responses");

        assertThat(responses.keySet()).containsExactlyInAnyOrder(codes.split(","));
    }

    @Test
    void errorResponsesAreTextPlainAndSuccessIsJson() throws Exception {
        String docs = apiDocs();
        int checked = 0;
        for (var path : operations().entrySet()) {
            for (String method : path.getValue().keySet()) {
                Map<String, Object> responses =
                        JsonPath.read(docs, "$.paths['" + path.getKey() + "']." + method + ".responses");
                for (String code : responses.keySet()) {
                    Map<String, Object> contentTypes = JsonPath.read(docs,
                            "$.paths['" + path.getKey() + "']." + method + ".responses['" + code + "'].content");
                    String where = method + " " + path.getKey() + " " + code;
                    if (code.equals("200")) {
                        assertThat(contentTypes.keySet()).as(where).containsExactly("application/json");
                    } else {
                        assertThat(contentTypes.keySet()).as(where).containsExactly("text/plain");
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(8);
    }

    /** S6, S12: the @Hidden catch-all and override-with-generic-response=false keep 500 off the operations. */
    @Test
    void catchAllIsNotAddedToOperations() throws Exception {
        String docs = apiDocs();
        for (var path : operations().entrySet()) {
            for (String method : path.getValue().keySet()) {
                Map<String, Object> responses =
                        JsonPath.read(docs, "$.paths['" + path.getKey() + "']." + method + ".responses");
                assertThat(responses).as(method + " " + path.getKey()).doesNotContainKeys("500", "default");
            }
        }
    }

    @Test
    void swaggerUiRedirectKeepsLibraryBehaviour() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/swagger-ui/index.html")));
    }

    @Test
    void swaggerUiIndexIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
