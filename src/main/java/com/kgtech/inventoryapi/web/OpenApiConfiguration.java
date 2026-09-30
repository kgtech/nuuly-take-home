package com.kgtech.inventoryapi.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

import org.springframework.context.annotation.Configuration;

/**
 * The API-wide OpenAPI info (D7), with the spec's title and version: it describes the whole API, so it sits in web
 * rather than on a controller (A37). springdoc reads it from this bean.
 */
@OpenAPIDefinition(info = @Info(title = OpenApiConfiguration.API_TITLE, version = OpenApiConfiguration.API_VERSION))
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String API_TITLE = "Inventory API";
    static final String API_VERSION = "1.0.0";
}
