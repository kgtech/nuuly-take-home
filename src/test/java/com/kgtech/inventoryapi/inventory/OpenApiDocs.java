package com.kgtech.inventoryapi.inventory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * H12: the two springdoc groups and how tests read them. Nothing reads the default all-routes /v3/api-docs. A group's
 * JSON is served at /v3/api-docs/{group} and its YAML at /v3/api-docs.yaml/{group} (not /v3/api-docs/{group}.yaml).
 */
final class OpenApiDocs {

    /** The unversioned API (the spec's four operations, info.version 1.0.0), exported to openapi.yaml. */
    static final String V1 = "inventory";

    /** The /v2 API (info.version 2.0.0), exported to openapi-v2.yaml. */
    static final String V2 = "inventory-v2";

    /** The app's port (compose.override.yaml, README); springdoc derives the documented server URL from the request. */
    static final int APP_PORT = 8080;

    private OpenApiDocs() {
    }

    /** A docs request issued as if on the app's port, so servers[0].url is http://localhost:8080. */
    static MockHttpServletRequestBuilder request(String path) {
        return get(path).with(request -> {
            request.setServerPort(APP_PORT);
            return request;
        });
    }

    static String json(MockMvc mvc, String group) throws Exception {
        return mvc.perform(request("/v3/api-docs/" + group))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    static byte[] yaml(MockMvc mvc, String group) throws Exception {
        return mvc.perform(request("/v3/api-docs.yaml/" + group))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String yaml) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    static Map<String, Object> exported(MockMvc mvc, String group) throws Exception {
        return parse(new String(yaml(mvc, group), UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object node, String... keys) {
        Object current = node;
        for (String key : keys) {
            assertThat(current).as("parent of " + key).isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(key);
        }
        assertThat(current).as(String.join(".", keys)).isInstanceOf(Map.class);
        return (Map<String, Object>) current;
    }

    /** Follows a local $ref (#/components/schemas/X) in the exported document. */
    static Map<String, Object> resolve(Map<String, Object> doc, Map<String, Object> schema) {
        Object ref = schema.get("$ref");
        if (ref == null) {
            return schema;
        }
        return map(doc, ((String) ref).substring(2).split("/"));
    }
}
