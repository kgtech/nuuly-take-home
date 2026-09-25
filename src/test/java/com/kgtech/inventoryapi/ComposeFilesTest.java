package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * D8, S4, S10: compose.yaml runs only Postgres (read by bootRun), compose.override.yaml adds the app (read by
 * `docker compose up --build`). Structural checks only; the running stack is verified by the manual smoke.
 */
class ComposeFilesTest {

    private static final Path COMPOSE = Path.of("compose.yaml");
    private static final Path OVERRIDE = Path.of("compose.override.yaml");
    private static final Path ENV = Path.of(".env");

    private static Object load(Path file) throws IOException {
        assertThat(file).as("%s must exist at the repo root", file).isRegularFile();
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        }
    }

    private static Map<String, Object> map(Object value, String what) {
        assertThat(value).as(what).isInstanceOf(Map.class);
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static List<?> list(Object value, String what) {
        assertThat(value).as(what).isInstanceOf(List.class);
        return (List<?>) value;
    }

    private static Map<String, Object> services(Path file) throws IOException {
        return map(map(load(file), file + " root").get("services"), file + " services");
    }

    private static Map<String, Object> service(Path file, String name) throws IOException {
        return map(services(file).get(name), file + " service " + name);
    }

    private static List<String> strings(Object value, String what) {
        return list(value, what).stream().map(String::valueOf).toList();
    }

    /** environment may be written as a map or as a list of KEY=VALUE. */
    private static Map<String, String> environment(Map<String, Object> service, String what) {
        Object env = service.get("environment");
        Map<String, String> result = new LinkedHashMap<>();
        if (env instanceof List<?> entries) {
            for (Object entry : entries) {
                String[] kv = String.valueOf(entry).split("=", 2);
                result.put(kv[0], kv.length > 1 ? kv[1] : null);
            }
        } else {
            map(env, what + " environment").forEach((k, v) -> result.put(k, String.valueOf(v)));
        }
        return result;
    }

    @Test
    void composeYamlDefinesOnlyPostgres() throws IOException {
        Map<String, Object> root = map(load(COMPOSE), "compose.yaml root");

        assertThat(map(root.get("services"), "compose.yaml services").keySet()).containsExactly("postgres");
        assertThat(root).as("no named volume: every run starts from an empty database").doesNotContainKey("volumes");
    }

    /** S10: the compose tag repeats the catalog's postgres version, which the tests also run against. */
    @Test
    void postgresImageMatchesTestImage() throws IOException {
        String expected = System.getProperty(TestcontainersConfiguration.IMAGE_PROPERTY);
        assertThat(expected).as(TestcontainersConfiguration.IMAGE_PROPERTY + " (set by Gradle)").isNotBlank();

        assertThat(service(COMPOSE, "postgres").get("image")).isEqualTo(expected);
    }

    /** S4: pg_isready over TCP, so the check stays red while the entrypoint's socket-only init server runs. */
    @Test
    void postgresHealthcheckUsesPgIsreadyOverTcp() throws IOException {
        Map<String, Object> healthcheck =
                map(service(COMPOSE, "postgres").get("healthcheck"), "postgres healthcheck");
        List<String> test = strings(healthcheck.get("test"), "postgres healthcheck test");

        assertThat(test).first().isEqualTo("CMD-SHELL");
        assertThat(String.join(" ", test)).contains("pg_isready").contains("-h 127.0.0.1");
        assertThat(healthcheck).containsKeys("interval", "timeout", "retries");
    }

    /** A random host port, so a local Postgres on 5432 doesn't block the reviewer run. */
    @Test
    void postgresPublishesContainerPort5432() throws IOException {
        assertThat(strings(service(COMPOSE, "postgres").get("ports"), "postgres ports")).containsExactly("5432");
    }

    @Test
    void overrideDefinesOnlyApp() throws IOException {
        assertThat(services(OVERRIDE).keySet()).containsExactly("app");
    }

    @Test
    void appBuildsFromRepoRoot() throws IOException {
        Object build = service(OVERRIDE, "app").get("build");
        Object context = build instanceof Map<?, ?> m ? m.get("context") : build;

        assertThat(context).as("app build context").isIn(".", "./");
    }

    @Test
    void appPublishes8080() throws IOException {
        assertThat(strings(service(OVERRIDE, "app").get("ports"), "app ports")).containsExactly("8080:8080");
    }

    @Test
    void appWaitsForHealthyPostgres() throws IOException {
        Map<String, Object> dependsOn =
                map(service(OVERRIDE, "app").get("depends_on"), "app depends_on (long form)");
        Map<String, Object> postgres = map(dependsOn.get("postgres"), "app depends_on postgres");

        assertThat(postgres.get("condition")).isEqualTo("service_healthy");
    }

    @Test
    void appDatasourceMatchesPostgresCredentials() throws IOException {
        Map<String, String> db = environment(service(COMPOSE, "postgres"), "postgres");
        Map<String, String> app = environment(service(OVERRIDE, "app"), "app");

        assertThat(db).containsEntry("POSTGRES_DB", "inventory")
                .containsEntry("POSTGRES_USER", "inventory")
                .containsEntry("POSTGRES_PASSWORD", "inventory");
        assertThat(app).containsEntry("SPRING_DATASOURCE_URL",
                        "jdbc:postgresql://postgres:5432/" + db.get("POSTGRES_DB"))
                .containsEntry("SPRING_DATASOURCE_USERNAME", db.get("POSTGRES_USER"))
                .containsEntry("SPRING_DATASOURCE_PASSWORD", db.get("POSTGRES_PASSWORD"));
    }

    /** S4: profiles would make `docker compose up` or bootRun silently skip a service. */
    @Test
    void noServiceUsesProfiles() throws IOException {
        for (Path file : List.of(COMPOSE, OVERRIDE)) {
            for (var entry : services(file).entrySet()) {
                assertThat(map(entry.getValue(), file + " " + entry.getKey()))
                        .as("%s service %s", file, entry.getKey())
                        .doesNotContainKey("profiles");
            }
        }
    }

    /** S4: COMPOSE_FILE or COMPOSE_PROFILES in .env would change which files bootRun and reviewers read. */
    @Test
    void envFileDoesNotSetComposeFileOrProfiles() throws IOException {
        if (!Files.exists(ENV)) {
            return;
        }
        try (Stream<String> lines = Files.lines(ENV)) {
            assertThat(lines.map(String::strip))
                    .noneMatch(line -> line.matches("(export\\s+)?COMPOSE_(FILE|PROFILES)\\s*=.*"));
        }
    }
}
