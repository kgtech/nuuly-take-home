package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Z2: the inventory domain package imports no Spring MVC, HTTP transport or inventory web types, and the
 * Idempotency-Key header name is written once, in HttpConstants. Reads the sources; Gradle runs tests from the
 * project directory.
 */
class PackageBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path DOMAIN = MAIN.resolve("com/kgtech/inventoryapi/inventory");
    private static final Path HTTP_CONSTANTS = MAIN.resolve("com/kgtech/inventoryapi/web/HttpConstants.java");
    private static final List<String> FORBIDDEN_IN_DOMAIN = List.of(
            "org.springframework.web.", "org.springframework.http.", "com.kgtech.inventoryapi.inventory.web.");

    @Test
    void domainPackageImportsNoWebOrHttpTypes() throws IOException {
        List<Path> domainFiles;
        try (Stream<Path> files = Files.list(DOMAIN)) {
            domainFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(domainFiles).as("domain sources").isNotEmpty();

        for (Path file : domainFiles) {
            List<String> imports = Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.replaceFirst("^import\\s+(static\\s+)?", ""))
                    .toList();
            for (String prefix : FORBIDDEN_IN_DOMAIN) {
                assertThat(imports).as("%s imports %s*", file, prefix).noneMatch(i -> i.startsWith(prefix));
            }
        }
    }

    @Test
    void idempotencyKeyLiteralOnlyInHttpConstants() throws IOException {
        List<Path> withLiteral;
        try (Stream<Path> files = Files.walk(MAIN)) {
            withLiteral = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(PackageBoundaryTest::containsIdempotencyKeyLiteral)
                    .toList();
        }
        assertThat(withLiteral).containsExactly(HTTP_CONSTANTS);
    }

    private static boolean containsIdempotencyKeyLiteral(Path file) {
        try {
            return Files.readString(file).contains("\"Idempotency-Key\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
