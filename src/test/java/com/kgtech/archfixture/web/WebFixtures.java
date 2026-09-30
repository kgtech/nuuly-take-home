package com.kgtech.archfixture.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ArchitectureTest's placement-rule fixtures (R2-06), the shared web package of a fixture layout rooted at
 * com.kgtech.archfixture. The root lies outside com.kgtech.inventoryapi, so no component scan of the application
 * (including the plain SpringApplication DurabilityAcrossServiceInstancesTest starts) ever registers them, and
 * ArchitectureTest's main-class import never contains them. Only the rule's self-tests import them.
 */
public final class WebFixtures {

    private WebFixtures() {
    }

    /** App-wide configuration where it belongs: an OpenAPI definition carrier that declares a container customizer. */
    @OpenAPIDefinition(info = @Info(title = "fixture", version = "0"))
    @Configuration(proxyBeanMethods = false)
    public static class WebHttpConfiguration {

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerSettings() {
            return factory -> {
            };
        }
    }
}
