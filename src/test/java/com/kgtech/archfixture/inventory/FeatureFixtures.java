package com.kgtech.archfixture.inventory;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.time.Clock;

import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * ArchitectureTest's placement-rule fixtures (R2-06) in a feature's domain package: one configuration a feature may
 * own, and one class of each app-wide HTTP kind, which belongs in web. See WebFixtures for why they live outside
 * com.kgtech.inventoryapi.
 */
public final class FeatureFixtures {

    private FeatureFixtures() {
    }

    /** A feature's own configuration: a Clock is no app-wide HTTP kind, so it may live in the feature (R2-06). */
    @Configuration(proxyBeanMethods = false)
    public static class ClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /** Declares a container customizer, which applies to every path. */
    @Configuration(proxyBeanMethods = false)
    public static class ContainerCustomizerConfiguration {

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerSettings() {
            return factory -> {
            };
        }
    }

    /** Declares a JsonMapperBuilderCustomizer, which shapes every feature's JSON. */
    @Configuration(proxyBeanMethods = false)
    public static class JsonConfiguration {

        @Bean
        JsonMapperBuilderCustomizer strictJson() {
            return builder -> {
            };
        }
    }

    /** Meta-annotated with @Configuration (through @AutoConfiguration) and declares a DispatcherServlet. */
    @AutoConfiguration
    public static class DispatcherAutoConfiguration {

        @Bean
        DispatcherServlet dispatcherServlet() {
            return new DispatcherServlet();
        }
    }

    @RestControllerAdvice
    public static class ErrorAdvice {
    }

    public static class ErrorPageValve extends ErrorReportValve {
    }

    public static class ContainerCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
        }
    }

    public static class Dispatcher extends DispatcherServlet {

        private static final long serialVersionUID = 1L;
    }

    @OpenAPIDefinition(info = @Info(title = "fixture", version = "0"))
    public static class ApiDefinition {
    }
}
