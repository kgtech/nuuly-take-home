package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.ALLOW;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.HttpHeaders.LOCATION;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Z2, issue #15: the inventory domain and idempotency packages import no Spring MVC or HTTP transport types, the
 * domain imports no inventory web types, and header names are written once: Idempotency-Key in HttpConstants, the
 * standard ones in Spring's HttpHeaders; tests pass the constants, not quoted names, to header calls. Reads the sources; Gradle runs tests from the
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
    private static final List<String> QUOTED_HEADER_NAMES = Stream.of(ACCEPT, ALLOW, CONTENT_TYPE, LINK,
                    LOCATION, IDEMPOTENCY_KEY)
            .map(PackageBoundaryTest::quoted)
            .toList();
    /**
     * A quoted header name (any case) as the first argument of a header call: MockMvc {@code .header(} and
     * {@code header().string/exists/doesNotExist(}, HttpRequest.Builder {@code .header(}, java.net.http.HttpHeaders
     * {@code firstValue/allValues(}, servlet {@code getHeader(s)/setHeader/addHeader/containsHeader(} and Spring
     * HttpHeaders {@code getFirst(}. Other string arguments that happen to equal a header name are not flagged.
     */
    private static final Pattern QUOTED_HEADER_NAME_USE = Pattern.compile(
            "(?i)(?:\\b(?:headers?|getHeaders?|setHeader|addHeader|containsHeader|firstValue|allValues|getFirst)"
                    + "|\\bheader\\(\\s*\\)\\s*\\.\\s*(?:string|exists|doesNotExist|stringValues|values))"
                    + "\\s*\\(\\s*(?:"
                    + String.join("|", QUOTED_HEADER_NAMES.stream().map(Pattern::quote).toList())
                    + ")");

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

    /**
     * Issue #15 (R3-3): tests name headers with HttpHeaders / HttpConstants, never a quoted literal (any case) as the
     * first argument of a header call. See {@link #isQuotedHeaderNameUse(String)}.
     */
    @Test
    void testsUseHeaderNameConstants() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (isQuotedHeaderNameUse(lines.get(i))) {
                        found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(found).as("quoted header names in header calls in src/test").isEmpty();
    }

    /** Rows are built from the constants so this file stays clean under its own scan. */
    static Stream<Arguments> quotedHeaderScannerCases() {
        return Stream.of(
                Arguments.of(".header(" + quoted(ACCEPT) + ", x)", true),
                Arguments.of("getHeader(" + quoted(CONTENT_TYPE).toLowerCase(Locale.ROOT) + ")", true),
                Arguments.of("firstValue(" + quoted(LOCATION) + ")", true),
                Arguments.of("{" + quoted(LOCATION).toLowerCase(Locale.ROOT) + ": 1}", false),
                Arguments.of("purchase(" + quoted(ACCEPT) + ", 1, null)", false),
                Arguments.of("List.of(" + quoted(ALLOW).toLowerCase(Locale.ROOT) + ")", false));
    }

    @ParameterizedTest
    @MethodSource("quotedHeaderScannerCases")
    void quotedHeaderScannerFlagsOnlyHeaderCalls(String line, boolean flagged) {
        assertThat(isQuotedHeaderNameUse(line)).as(line).isEqualTo(flagged);
    }

    static boolean isQuotedHeaderNameUse(String line) {
        return QUOTED_HEADER_NAME_USE.matcher(line).find();
    }

    private static String quoted(String name) {
        return '"' + name + '"';
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
