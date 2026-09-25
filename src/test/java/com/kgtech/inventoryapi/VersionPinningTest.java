package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC3: only gradle/libs.versions.toml and gradle-wrapper.properties contain version numbers (S10),
 * plus the D1, D2 and R6 build checks. Paths are relative to the project directory (Gradle's test
 * working directory).
 */
class VersionPinningTest {

    private static final Path ROOT = Path.of("");
    private static final Path CATALOG = Path.of("gradle", "libs.versions.toml");
    private static final Path WRAPPER_PROPERTIES = Path.of("gradle", "wrapper", "gradle-wrapper.properties");

    /** Applied to every scanned file. */
    private static final List<Pattern> VERSION_PATTERNS = List.of(
            Pattern.compile("\\b(postgres|postgresql|eclipse-temurin|openjdk|gradle|amazoncorretto)[\\w./-]*:\\d"),
            Pattern.compile("\"[\\w.-]+:[\\w.-]+:\\d"),
            Pattern.compile("JavaLanguageVersion\\.of\\(\\s*\\d"),
            Pattern.compile("JavaVersion\\.VERSION_\\d"),
            Pattern.compile("(source|target)Compatibility\\s*=\\s*\"?\\d"),
            Pattern.compile("--release\\s+\\d"),
            Pattern.compile("\\bversion\\s*(=|\\()?\\s*\"\\d"));

    /** Applied to non-.java files only, so float literals in Java stay legal. */
    private static final Pattern DOTTED_NUMBER = Pattern.compile("\\b\\d+\\.\\d+\\b");

    private static final Pattern WEB_STARTER = Pattern.compile("spring-boot-starter-web(?![\\w-])");

    private static final Set<String> BOM_MANAGED_GROUPS = Set.of(
            "org.springframework.boot", "org.flywaydb", "org.postgresql", "org.testcontainers", "org.junit.platform");

    static boolean flags(String fileName, String line) {
        for (Pattern pattern : VERSION_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return !fileName.endsWith(".java") && DOTTED_NUMBER.matcher(line).find();
    }

    /** The files in the AC3 scan scope. */
    static List<Path> scannedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "Dockerfile", ".env")) {
            Path file = ROOT.resolve(name);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        try (Stream<Path> rootFiles = Files.list(ROOT.toAbsolutePath())) {
            rootFiles.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("compose") && name.endsWith(".yaml");
                    })
                    .forEach(files::add);
        }
        Set<String> gradleExcluded = Set.of("gradle-wrapper.properties", "gradle-wrapper.jar", "libs.versions.toml");
        files.addAll(walk(Path.of("gradle"), p -> !gradleExcluded.contains(p.getFileName().toString())));
        files.addAll(walk(Path.of("src"), p -> !p.getFileName().toString().equals("VersionPinningTest.java")));
        return files;
    }

    private static List<Path> walk(Path dir, Predicate<Path> include) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).filter(include).toList();
        }
    }

    @Test
    void onlyCatalogAndWrapperPropertiesContainVersionNumbers() throws IOException {
        List<Path> files = scannedFiles();
        assertThat(files).as("scan scope").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (flags(file.getFileName().toString(), lines.get(i))) {
                    offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }

        assertThat(offenders).as("version numbers outside the catalog and wrapper properties").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "image: postgres:18-alpine",
        "FROM eclipse-temurin:25-jre",
        "JavaLanguageVersion.of(25)",
        "id(\"org.springframework.boot\") version \"4.1.1\"",
        "implementation(\"org.flywaydb:flyway-core:11.0.0\")",
        "version = \"0.0.1-SNAPSHOT\""
    })
    void scannerDetectsKnownVersionForms(String line) {
        assertThat(flags("build.gradle.kts", line)).as(line).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"postgres:${libs.versions.postgres.get()}\"",
        "JavaLanguageVersion.of(libs.versions.java.get().toInt())",
        "9223372036854775807"
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

        Pattern moduleGroup = Pattern.compile("module\\s*=\\s*\"([^:\"]+):");
        Pattern explicitGroup = Pattern.compile("group\\s*=\\s*\"([^\"]+)\"");
        List<String> offenders = new ArrayList<>();
        libraries.forEach((alias, value) -> {
            Matcher module = moduleGroup.matcher(value);
            Matcher group = explicitGroup.matcher(value);
            String groupId = module.find() ? module.group(1) : group.find() ? group.group(1) : null;
            if (BOM_MANAGED_GROUPS.contains(groupId) && value.contains("version")) {
                offenders.add(alias + " = " + value);
            }
        });

        assertThat(offenders).as("BOM-managed libraries with a version").isEmpty();
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

        List<Path> files = new ArrayList<>(scannedFiles());
        files.add(CATALOG);
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
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
