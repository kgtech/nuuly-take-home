package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC3: outside docs and the generated wrapper scripts, only gradle/libs.versions.toml and
 * gradle-wrapper.properties contain version numbers (S10),
 * plus the D1, D2 and R6 build checks. Paths are relative to the project directory (Gradle's test
 * working directory).
 */
class VersionPinningTest {

    private static final Path ROOT = Path.of("");
    private static final Path CATALOG = Path.of("gradle", "libs.versions.toml");
    private static final Path WRAPPER_PROPERTIES = Path.of("gradle", "wrapper", "gradle-wrapper.properties");

    /** Applied to every scanned file. */
    private static final List<Pattern> VERSION_PATTERNS = List.of(
            // image tag; "//" before the name is a URL host (jdbc:postgresql://postgres:5432), not an image
            Pattern.compile("(?<!//)\\b(postgres|postgresql|eclipse-temurin|openjdk|gradle|amazoncorretto)[\\w./-]*:\\d"),
            Pattern.compile("\"[\\w.-]+:[\\w.-]+:\\d"),
            Pattern.compile("JavaLanguageVersion\\.of\\(\\s*\"?\\d"),
            Pattern.compile("JavaVersion\\.VERSION_\\d"),
            Pattern.compile("toVersion\\(\\s*\"?\\d"),
            Pattern.compile("jvmToolchain\\(\\s*\\d"),
            Pattern.compile("(source|target)Compatibility\\s*=\\s*\"?\\d"),
            Pattern.compile("--release\\s+\\d"),
            Pattern.compile("release(\\.set\\(|\\s*=)\\s*\\d"),
            Pattern.compile("\\bversion\\s*(=|\\()?\\s*\"\\d"));

    /** Applied to non-.java files only, so float literals in Java stay legal. */
    private static final Pattern DOTTED_NUMBER = Pattern.compile("\\b\\d+\\.\\d+\\b");

    private static final Pattern WEB_STARTER = Pattern.compile("spring-boot-starter-web(?![\\w-])");

    /** Group prefixes whose artifacts the Spring Boot BOM manages (S10). */
    private static final List<String> BOM_MANAGED_GROUP_PREFIXES = List.of(
            "org.springframework", "org.hibernate", "tools.jackson", "com.fasterxml.jackson", "org.flywaydb",
            "org.postgresql", "org.testcontainers", "org.junit", "org.assertj", "org.mockito");

    private static final Pattern STRING_NOTATION = Pattern.compile("^\"([^:\"]+):([^:\"]+)(?::([^\"]*))?\"$");
    private static final Pattern MODULE_GROUP = Pattern.compile("\\bmodule\\s*=\\s*\"([^:\"]+):");
    private static final Pattern EXPLICIT_GROUP = Pattern.compile("\\bgroup\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_KEY = Pattern.compile("(^|[{,\\s])version(\\.ref)?\\s*=");

    /** Directory names never scanned: VCS, build output, tool state and docs (AC3). */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "build", ".gradle", ".kotlin", ".idea", ".claude", ".orchestrator", "ai", "docs");

    /** File names never scanned: generated wrapper, the two allowed files, this test and OS clutter (AC3). */
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "gradlew", "gradlew.bat", "gradle-wrapper.jar", "gradle-wrapper.properties", "libs.versions.toml",
            "VersionPinningTest.java", ".DS_Store");

    static boolean flags(String fileName, String line) {
        for (Pattern pattern : VERSION_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return !fileName.endsWith(".java") && DOTTED_NUMBER.matcher(line).find();
    }

    /** The files in the AC3 scan scope: the whole tree under {@code root} minus the exclusions. */
    static List<Path> scannedFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                return !dir.equals(root) && name != null && EXCLUDED_DIRS.contains(name.toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && !name.endsWith(".md") && !EXCLUDED_FILES.contains(name)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static List<String> lines(Path file) throws IOException {
        // lenient decode: a binary file must not abort the scan
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void onlyCatalogAndWrapperPropertiesContainVersionNumbers() throws IOException {
        List<Path> files = scannedFiles(ROOT.toAbsolutePath());
        assertThat(files).as("scan scope").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = lines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (flags(file.getFileName().toString(), lines.get(i))) {
                    offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }

        assertThat(offenders).as("version numbers outside the catalog and wrapper properties").isEmpty();
    }

    @Test
    void scannedFilesIncludeUnknownVersionBearingFiles(@TempDir Path root) throws IOException {
        List<String> included = List.of(
                "compose.yml", "docker-compose.yaml", "docker/Dockerfile.dev", ".github/workflows/ci.yml",
                ".sdkmanrc", ".java-version");
        List<String> excluded = List.of("README.md", "ai/x.md", "gradlew");
        for (String name : Stream.concat(included.stream(), excluded.stream()).toList()) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
        }

        List<String> scanned = scannedFiles(root).stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .toList();

        assertThat(scanned).containsExactlyInAnyOrderElementsOf(included);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "image: postgres:18-alpine",
        "FROM eclipse-temurin:25-jre",
        "JavaLanguageVersion.of(25)",
        "id(\"org.springframework.boot\") version \"4.1.1\"",
        "implementation(\"org.flywaydb:flyway-core:11.0.0\")",
        "version = \"0.0.1-SNAPSHOT\"",
        "options.release = 25",
        "options.release.set(25)",
        "JavaLanguageVersion.of(\"25\")",
        "sourceCompatibility = JavaVersion.toVersion(25)",
        "kotlin { jvmToolchain(25) }"
    })
    void scannerDetectsKnownVersionForms(String line) {
        assertThat(flags("build.gradle.kts", line)).as(line).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"postgres:${libs.versions.postgres.get()}\"",
        "JavaLanguageVersion.of(libs.versions.java.get().toInt())",
        "9223372036854775807",
        "SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/inventory"
    })
    void scannerIgnoresNonVersions(String line) {
        assertThat(flags("build.gradle.kts", line)).as(line).isFalse();
    }

    @Test
    void catalogPinsJava25AndBoot41() throws IOException {
        Map<String, String> versions = catalogSection("versions");

        assertThat(versions.get("java")).isEqualTo("\"25\"");
        assertThat(unquote(versions.get("spring-boot"))).matches("^4\\.1\\.\\d+$");
        assertThat(unquote(versions.get("postgres"))).isNotBlank();
    }

    @Test
    void bomManagedLibrariesDeclareNoVersion() throws IOException {
        Map<String, String> libraries = catalogSection("libraries");
        assertThat(libraries).isNotEmpty();

        List<String> offenders = new ArrayList<>();
        libraries.forEach((alias, value) -> {
            assertThat(groupOf(value)).as("group of library '%s' (%s)", alias, value).isPresent();
            if (declaresBomManagedVersion(value)) {
                offenders.add(alias + " = " + value);
            }
        });

        assertThat(offenders).as("BOM-managed libraries with a version").isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{ module = \"org.hibernate.validator:hibernate-validator\", version = \"9.0.0\" } | true",
        "\"org.springframework:spring-core:7.0.0\"                                       | true",
        "\"org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0\"                     | false",
        "{ module = \"org.postgresql:postgresql\" }                                      | false"
    })
    void declaresBomManagedVersionClassifiesCatalogEntries(String value, boolean expected) {
        assertThat(declaresBomManagedVersion(value)).as(value).isEqualTo(expected);
    }

    /**
     * True when a raw [libraries] value names a BOM-managed group and declares a version, either as the third part
     * of string notation ("g:a:v") or as a version / version.ref key.
     *
     * @throws IllegalArgumentException when the group can't be determined
     */
    static boolean declaresBomManagedVersion(String value) {
        String group = groupOf(value)
                .orElseThrow(() -> new IllegalArgumentException("cannot determine group of " + value));
        boolean bomManaged = BOM_MANAGED_GROUP_PREFIXES.stream()
                .anyMatch(prefix -> group.equals(prefix) || group.startsWith(prefix + "."));
        if (!bomManaged) {
            return false;
        }
        Matcher string = STRING_NOTATION.matcher(value.strip());
        if (string.matches()) {
            return string.group(3) != null && !string.group(3).isBlank();
        }
        return VERSION_KEY.matcher(value).find();
    }

    private static Optional<String> groupOf(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher string = STRING_NOTATION.matcher(value.strip());
        if (string.matches()) {
            return Optional.of(string.group(1));
        }
        Matcher module = MODULE_GROUP.matcher(value);
        if (module.find()) {
            return Optional.of(module.group(1));
        }
        Matcher group = EXPLICIT_GROUP.matcher(value);
        return group.find() ? Optional.of(group.group(1)) : Optional.empty();
    }

    @Test
    void wrapperPinsGradle91OrLater() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(WRAPPER_PROPERTIES, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String url = properties.getProperty("distributionUrl");
        assertThat(url).isNotNull();

        Matcher matcher = Pattern.compile("gradle-(\\d+)\\.(\\d+)(\\.\\d+)?-(bin|all)\\.zip").matcher(url);
        assertThat(matcher.find()).as(url).isTrue();
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        assertThat(major > 9 || (major == 9 && minor >= 1)).as(url).isTrue();
    }

    @Test
    void buildUsesKotlinDsl() {
        assertThat(ROOT.resolve("build.gradle.kts")).isRegularFile();
        assertThat(ROOT.resolve("settings.gradle.kts")).isRegularFile();
        assertThat(ROOT.resolve("build.gradle")).doesNotExist();
        assertThat(ROOT.resolve("settings.gradle")).doesNotExist();
    }

    @Test
    void usesWebmvcStarterNotWeb() throws IOException {
        String catalog = Files.readString(CATALOG, StandardCharsets.UTF_8);
        assertThat(catalog).contains("spring-boot-starter-webmvc");

        List<Path> files = new ArrayList<>(scannedFiles(ROOT.toAbsolutePath()));
        files.add(CATALOG);
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = lines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (WEB_STARTER.matcher(lines.get(i)).find()) {
                    offenders.add(file + ":" + (i + 1));
                }
            }
        }
        assertThat(offenders).as("spring-boot-starter-web references").isEmpty();
    }

    /** Reads one [section] of the version catalog as key → raw value (single-line entries). */
    private static Map<String, String> catalogSection(String section) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        String current = null;
        for (String raw : Files.readAllLines(CATALOG, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).strip();
                continue;
            }
            int eq = line.indexOf('=');
            if (section.equals(current) && eq > 0) {
                entries.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return entries;
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        return value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
