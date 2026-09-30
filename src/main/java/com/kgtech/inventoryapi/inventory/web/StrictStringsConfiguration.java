package com.kgtech.inventoryapi.inventory.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

/**
 * G13 for string fields (critique F-spec-01): a JSON number or boolean is never stringified into a string field, the
 * same way a JSON string is never coerced into a number. allow-coercion-of-scalars only governs numeric and boolean
 * targets, so the string target is configured here.
 */
@Configuration(proxyBeanMethods = false)
class StrictStringsConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictStrings() {
        return builder -> builder.withCoercionConfig(String.class, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
