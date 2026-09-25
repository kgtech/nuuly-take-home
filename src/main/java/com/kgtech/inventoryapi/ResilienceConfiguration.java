package com.kgtech.inventoryapi;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/** Enables @Retryable on stock writes (Y2). */
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
class ResilienceConfiguration {
}
