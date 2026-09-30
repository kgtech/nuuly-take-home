package com.kgtech.archfixture.inventory.web;

import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ArchitectureTest's placement-rule fixtures (R2-06) in a feature's web package, which is not the shared web package.
 * See WebFixtures for why they live outside com.kgtech.inventoryapi.
 */
public final class FeatureWebFixtures {

    private FeatureWebFixtures() {
    }

    /** Declares the error-page valve, which answers for every path. */
    @Configuration(proxyBeanMethods = false)
    public static class ErrorPageConfiguration {

        @Bean
        ErrorReportValve errorPage() {
            return new ErrorReportValve();
        }
    }
}
