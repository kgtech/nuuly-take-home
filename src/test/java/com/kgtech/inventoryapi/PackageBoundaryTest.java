package com.kgtech.inventoryapi;

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
 * Z2, issue #15, A39: the source-text half of the layout guard; ArchitectureTest checks the compiled classes. javac
 * inlines constants and drops comments, so only the sources show a constant-only import or a quoted name. The
 * inventory domain and idempotency packages name no Spring MVC, HTTP, servlet or Tomcat type, the domain names no web
 * package, web and idempotency name no inventory code and no feature route or answer in a string literal (R1-05),
 * and header names are written once: Idempotency-Key in HttpConstants, the standard ones in Spring's HttpHeaders;
 * tests pass the constants, not quoted names, to header calls. Every scan walks sub-packages too. Reads the sources;
 * Gradle runs tests from the project directory.
 */
class PackageBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path TEST = Path.of("src/test/java");
    private static final Path DOMAIN = MAIN.resolve("com/kgtech/inventoryapi/inventory");
    /** The feature's web layer: a sub-package of the domain directory, but not the domain (Z2). */
    private static final Path DOMAIN_WEB = DOMAIN.resolve("web");
    private static final Path IDEMPOTENCY = MAIN.resolve("com/kgtech/inventoryapi/idempotency");
    private static final Path WEB = MAIN.resolve("com/kgtech/inventoryapi/web");
    private static final Path HTTP_CONSTANTS = WEB.resolve("HttpConstants.java");
    /** The transport packages ArchitectureTest forbids in the domain and idempotency, as source prefixes. */
    private static final List<String> FORBIDDEN_HTTP = List.of("org.springframework.web.", "org.springframework.http.",
            "jakarta.servlet.", "org.apache.catalina.", "org.apache.tomcat.");
    private static final String INVENTORY_WEB = "com.kgtech.inventoryapi.inventory.web.";
    private static final String SHARED_WEB = "com.kgtech.inventoryapi.web.";
    /** Any inventory type, the domain's or its web layer's. */
    private static final String INVENTORY = "com.kgtech.inventoryapi.inventory.";
    /** Every route of the inventory feature: the spec's /inventory/** and v2's /v2/inventory/**. */
    private static final String FEATURE_ROUTE = "/inventory";
    /** The inventory feature's error answers, by their opening words: G6's 404 and 400, DESIGN-V2 §8's 409 and 412. */
    private static final List<String> FEATURE_ANSWERS = List.of("SKU not found", "Insufficient inventory",
            "SKU already exists", "Details changed");
    private static final String TEXT_BLOCK_QUOTES = "\"\"\"";
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
        forbidden.add(SHARED_WEB);
        assertReferencesNone(sources(DOMAIN, DOMAIN_WEB), forbidden);
    }

    /** Issue #15 (R3-1): StoredResponse and the rest of the idempotency package stay free of Spring HTTP types. */
    @Test
    void idempotencyPackageImportsNoWebOrHttpTypes() throws IOException {
        List<String> forbidden = new ArrayList<>(FORBIDDEN_HTTP);
        forbidden.add(SHARED_WEB);
        assertReferencesNone(sources(IDEMPOTENCY), forbidden);
    }

    /**
     * A39: web and idempotency never name inventory code. javac inlines constants, so an import used only for a
     * constant (e.g. a path from InventoryApi) leaves no trace in the bytecode ArchitectureTest reads; the source
     * keeps it.
     */
    @Test
    void sharedPackagesImportNoFeatureCode() throws IOException {
        assertReferencesNone(sources(WEB), List.of(INVENTORY));
        assertReferencesNone(sources(IDEMPOTENCY), List.of(INVENTORY));
    }

    /**
     * R1-05: web and idempotency name no feature route in code: no string literal holds /inventory (the spec's
     * routes, and v2's /v2/inventory). Only literals count: a comment may name a route (the advice's Javadoc says it
     * serves every path), and the advice's library paths (/actuator/**, the springdoc paths) name no feature.
     */
    @Test
    void sharedPackagesNameNoFeatureRoute() throws IOException {
        assertLiteralsContainNone(sharedSources(), List.of(FEATURE_ROUTE));
    }

    /**
     * R1-05: the inventory feature's answers (G6's 404 and 400 texts, DESIGN-V2 §8's 409 and 412 texts) live in
     * inventory.web; web keeps G6's generic texts, which every path uses.
     */
    @Test
    void sharedPackagesNameNoFeatureAnswer() throws IOException {
        assertLiteralsContainNone(sharedSources(), FEATURE_ANSWERS);
    }

    /** The literal scan reads the real sources: web's literals include the advice's library paths and G6's 400. */
    @Test
    void literalScanReadsWebSources() throws IOException {
        List<String> literals = sources(WEB).stream().flatMap(p -> stringLiterals(read(p)).stream()).toList();
        assertThat(literals).contains("/actuator/**", "/swagger-ui/**", "Invalid request");
    }

    /** Rows are Java source fragments and the literals the scanner must return, raw, in order. */
    static Stream<Arguments> stringLiteralScannerCases() {
        return Stream.of(
                Arguments.of("String a = \"/inventory/{skuId}\";", List.of("/inventory/{skuId}")),
                Arguments.of("String a = \"x\" + \"y\";", List.of("x", "y")),
                Arguments.of("String e = \"\";", List.of("")),
                Arguments.of("// see \"/inventory\"\nint x;", List.of()),
                Arguments.of("/** 404 \"SKU not found\" */ int x;", List.of()),
                Arguments.of("String u = \"http://x\"; // \"y\"", List.of("http://x")),
                Arguments.of("char q = '\"'; String s = \"a\\\"b\";", List.of("a\\\"b")),
                Arguments.of("char q = '\\''; String s = \"c\";", List.of("c")),
                Arguments.of("String t = \"\"\"\n    /inventory\n    \"\"\";", List.of("\n    /inventory\n    ")));
    }

    @ParameterizedTest
    @MethodSource("stringLiteralScannerCases")
    void stringLiteralScannerReadsOnlyLiterals(String source, List<String> literals) {
        assertThat(stringLiterals(source)).as(source).isEqualTo(literals);
    }

    /** The walk reaches sub-packages and leaves out the excluded ones (a scan that saw nothing proves nothing). */
    @Test
    void sourceWalkIsRecursiveAndHonoursExclusions() throws IOException {
        assertThat(sources(DOMAIN)).contains(DOMAIN_WEB.resolve("InventoryController.java"));
        assertThat(sources(DOMAIN, DOMAIN_WEB)).contains(DOMAIN.resolve("InventoryService.java"))
                .noneMatch(p -> p.startsWith(DOMAIN_WEB));
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

    /** Every .java file under {@code dir}, sub-packages included, except those under an {@code excluded} directory. */
    private static List<Path> sources(Path dir, Path... excluded) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> Stream.of(excluded).noneMatch(p::startsWith))
                    .toList();
        }
    }

    /** The shared packages' sources: web and idempotency, which serve the feature but name none of it. */
    private static List<Path> sharedSources() throws IOException {
        return Stream.concat(sources(WEB).stream(), sources(IDEMPOTENCY).stream()).toList();
    }

    /**
     * The contents of the string literals and text blocks in {@code source}, in order and raw (escapes kept as
     * written); comments and char literals are skipped. Enough of Java's lexical grammar for a source scan.
     */
    static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                int lineEnd = source.indexOf('\n', i);
                i = lineEnd < 0 ? source.length() : lineEnd + 1;
            } else if (source.startsWith("/*", i)) {
                int commentEnd = source.indexOf("*/", i + 2);
                i = commentEnd < 0 ? source.length() : commentEnd + 2;
            } else if (source.startsWith(TEXT_BLOCK_QUOTES, i)) {
                int end = closing(source, i + TEXT_BLOCK_QUOTES.length(), TEXT_BLOCK_QUOTES);
                literals.add(source.substring(i + TEXT_BLOCK_QUOTES.length(), end));
                i = end + TEXT_BLOCK_QUOTES.length();
            } else if (source.charAt(i) == '"') {
                int end = closing(source, i + 1, "\"");
                literals.add(source.substring(i + 1, end));
                i = end + 1;
            } else if (source.charAt(i) == '\'') {
                i = closing(source, i + 1, "'") + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    /** The index of the first unescaped {@code delimiter} at or after {@code from}, or the end of {@code source}. */
    private static int closing(String source, int from, String delimiter) {
        int i = from;
        while (i < source.length() && !source.startsWith(delimiter, i)) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i, source.length());
    }

    private static void assertLiteralsContainNone(List<Path> sources, List<String> forbidden) {
        assertThat(sources).as("sources to scan").isNotEmpty();

        List<String> found = new ArrayList<>();
        for (Path file : sources) {
            for (String literal : stringLiterals(read(file))) {
                forbidden.stream().filter(literal::contains)
                        .forEach(text -> found.add(file + ": " + quoted(literal) + " contains " + quoted(text)));
            }
        }
        assertThat(found).as("string literals naming %s", forbidden).isEmpty();
    }

    private static void assertReferencesNone(List<Path> sources, List<String> forbidden) {
        assertThat(sources).as("sources to scan").isNotEmpty();

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
