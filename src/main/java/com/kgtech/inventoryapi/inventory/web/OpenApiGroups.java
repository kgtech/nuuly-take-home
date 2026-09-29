package com.kgtech.inventoryapi.inventory.web;

import static com.kgtech.inventoryapi.inventory.InventoryService.MAX_LIMIT;

import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two springdoc groups, one document each (H12): the unversioned spec API (openapi.yaml, 1.0.0) and /v2
 * (openapi-v2.yaml, 2.0.0). Each carries its own info here; a class-level @OpenAPIDefinition would leak into both.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiGroups {

    static final String GROUP = "inventory";
    static final String GROUP_V2 = "inventory-v2";

    @Bean
    GroupedOpenApi inventoryGroup() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .pathsToMatch("/inventory", "/inventory/**")
                .addOpenApiCustomizer(api -> api.info(new Info().title("Inventory API").version("1.0.0")
                        .description("The take-home spec's API, with these deviations: GET /inventory returns at most "
                                + MAX_LIMIT + " SKUs per response and pages with the after cursor and the Link header. "
                                + "Write operations reject an Idempotency-Key. Idempotent writes, SKU details and "
                                + "conditional requests are in the /v2 API (openapi-v2.yaml).")))
                .addOpenApiCustomizer(OpenApiGroups::dropIntegerFormats)
                .build();
    }

    @Bean
    GroupedOpenApi inventoryV2Group() {
        return GroupedOpenApi.builder()
                .group(GROUP_V2)
                .pathsToMatch("/v2/**")
                .addOpenApiCustomizer(api -> api.info(new Info().title("Inventory API v2").version("2.0.0")
                        .description("The /v2 API: idempotent writes, SKU details and conditional requests.")))
                .build();
    }

    /** The spec writes plain "type: integer"; springdoc adds int32/int64 (SpecConformanceTest). v2 keeps them. */
    @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models exposes raw Schema
    private static void dropIntegerFormats(OpenAPI api) {
        api.getComponents().getSchemas().values().forEach(schema -> {
            Map<String, Schema> properties = schema.getProperties();
            if (properties != null) {
                properties.values().stream()
                        .filter(property -> "integer".equals(property.getType())
                                || (property.getTypes() != null && property.getTypes().contains("integer")))
                        .forEach(property -> property.setFormat(null));
            }
        });
    }
}
