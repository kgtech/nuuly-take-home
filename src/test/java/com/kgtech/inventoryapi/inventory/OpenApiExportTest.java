package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.inventory.OpenApiDocs.exported;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.V1;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.V2;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.map;
import static com.kgtech.inventoryapi.inventory.OpenApiDocs.yaml;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.kgtech.inventoryapi.IntegrationTest;

/**
 * D7, H12: each springdoc group is exported to a committed file, byte-stable: group inventory to openapi.yaml, group
 * inventory-v2 to openapi-v2.yaml. Replaces ApiDocsTest.openApiYamlIsRegeneratedAndCommitted, which wrote the
 * default all-routes document.
 */
@IntegrationTest
@AutoConfigureMockMvc
class OpenApiExportTest {

    private static final String GROUP_AND_FILE = V1 + ", openapi.yaml\n" + V2 + ", openapi-v2.yaml";

    @Autowired
    MockMvc mvc;

    /**
     * The test writes the group's YAML to its file at the project root (Gradle's test working directory) and fails
     * when the committed file was missing or different, so a stale export can't be merged. Two requests give the same
     * bytes.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = ',', textBlock = GROUP_AND_FILE)
    void exportIsStableAndCommitted(String group, String file) throws Exception {
        assertThat(Path.of("settings.gradle.kts")).as("test working directory is the project root").exists();
        byte[] current = yaml(mvc, group);
        assertThat(yaml(mvc, group)).as("second request").isEqualTo(current);
        Path target = Path.of(file);
        byte[] previous = Files.exists(target) ? Files.readAllBytes(target) : null;

        Files.write(target, current);

        assertThat(previous != null && Arrays.equals(previous, current))
                .withFailMessage(file + " regenerated; commit it")
                .isTrue();
    }

    /** R1-2 (#8): each export declares one server, the app on port 8080, not MockMvc's default http://localhost. */
    @ParameterizedTest
    @ValueSource(strings = {V1, V2})
    @SuppressWarnings("unchecked")
    void exportDeclaresServerOnPort8080(String group) throws Exception {
        Object servers = exported(mvc, group).get("servers");

        assertThat(servers).as(group + " servers").isInstanceOf(List.class);
        assertThat((List<Map<String, Object>>) servers).singleElement()
                .satisfies(server -> assertThat(server.get("url")).isEqualTo("http://localhost:8080"));
    }

    /** OQ2 (#8): paths and component keys are sorted (springdoc.writer-with-order-by-keys). */
    @ParameterizedTest
    @ValueSource(strings = {V1, V2})
    void exportKeysAreSorted(String group) throws Exception {
        Map<String, Object> doc = exported(mvc, group);

        assertThat(List.copyOf(map(doc, "paths").keySet())).as("paths").isSorted();
        Map<String, Object> components = map(doc, "components");
        assertThat(List.copyOf(components.keySet())).as("components").isSorted();
        for (String section : components.keySet()) {
            assertThat(List.copyOf(map(components, section).keySet())).as("components." + section).isSorted();
        }
    }
}
