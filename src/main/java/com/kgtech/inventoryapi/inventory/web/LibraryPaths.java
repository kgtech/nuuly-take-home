package com.kgtech.inventoryapi.inventory.web;

import java.util.List;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** The paths that keep library behaviour (S6, G10, C1): actuator and springdoc. /error is not one of them. */
final class LibraryPaths {

    private static final List<PathPattern> PATTERNS = List.of(
            "/actuator/**", "/v3/api-docs/**", "/v3/api-docs.yaml/**", "/swagger-ui.html", "/swagger-ui/**")
            .stream()
            .map(PathPatternParser.defaultInstance::parse)
            .toList();

    private LibraryPaths() {
    }

    static boolean matches(PathContainer path) {
        return PATTERNS.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
