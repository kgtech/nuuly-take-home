package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.ALLOW;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.LOCATION;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Z2, issue #15: the inventory domain and idempotency packages import no Spring MVC or HTTP transport types, the
 * domain imports no inventory web types, and header names are written once: Idempotency-Key in HttpConstants, the
 * standard ones in Spring's HttpHeaders; tests use the constants too. Reads the sources; Gradle runs tests from the
 * project directory.
 */
class PackageBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path TEST = Path.of("src/test/java");
    private static final Path DOMAIN = MAIN.resolve("com/kgtech/inventoryapi/inventory");
    private static final Path IDEMPOTENCY = MAIN.resolve("com/kgtech/inventoryapi/idempotency");
    private static final Path HTTP_CONSTANTS = MAIN.resolve("com/kgtech/inventoryapi/web/HttpConstants.java");
    private static final List<String> FORBIDDEN_HTTP = List.of("org.springframework.web.", "org.springframework.http.");
    private static final String INVENTORY_WEB = "com.kgtech.inventoryapi.inventory.web.";
    /** Built from the constants so this file does not contain the quoted names it looks for. */
    private static final List<String> QUOTED_HEADER_NAMES = Stream.of(ACCEPT, ALLOW, CONTENT_TYPE, LOCATION,
                    IDEMPOTENCY_KEY)
            .map(name -> ('"' + name + '"').toLowerCase(Locale.ROOT))
            .toList();

    @Test
    void domainPackageImportsNoWebOrHttpTypes() throws IOException {
        List<String> forbidden = new ArrayList<>(FORBIDDEN_HTTP);
        forbidden.add(INVENTORY_WEB);
        assertReferencesNone(DOMAIN, forbidden);
    }

    /** Issue #15 (R3-1): StoredResponse and the rest of the idempotency package stay free of Spring HTTP types. */
    @Test
    void idempotencyPackageImportsNoWebOrHttpTypes() throws IOException {
        assertReferencesNone(IDEMPOTENCY, FORBIDDEN_HTTP);
    }

    @Test
    void idempotencyKeyLiteralOnlyInHttpConstants() throws IOException {
        String quoted = '"' + IDEMPOTENCY_KEY + '"';
        List<Path> withLiteral;
        try (Stream<Path> files = Files.walk(MAIN)) {
            withLiteral = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains(quoted))
                    .toList();
        }
        assertThat(withLiteral).containsExactly(HTTP_CONSTANTS);
    }

    /** Issue #15 (R3-3): tests name headers with HttpHeaders / HttpConstants, never a quoted literal (any case). */
    @Test
    void testsUseHeaderNameConstants() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).toLowerCase(Locale.ROOT);
                    for (String quoted : QUOTED_HEADER_NAMES) {
                        if (line.contains(quoted)) {
                            found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                        }
                    }
                }
            }
        }
        assertThat(found).as("quoted header names in src/test").isEmpty();
    }

    private static void assertReferencesNone(Path dir, List<String> forbidden) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(dir)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("%s sources", dir).isNotEmpty();

        for (Path file : sources) {
            String source = read(file);
            for (String prefix : forbidden) {
                assertThat(source).as("%s references %s* (import or qualified name)", file, prefix)
                        .doesNotContain(prefix);
            }
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
