package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.inventory.OpenApiDocs.V1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.kgtech.inventoryapi.IntegrationTest;

/**
 * H12, invariant 4: the unversioned API documents exactly the spec's four operations. The YAML block of
 * docs/NUULY-ASSESSMENT-README-JUL-2026.md and the export of group "inventory" (openapi.yaml) are normalized and
 * compared as flat pointer to value maps. Only the differences in {@link #ALLOWED}, each with its decision ID, may
 * occur, and each of them must occur (an allowed difference that stops occurring fails the test too). Everything
 * else must be identical: summaries, descriptions and examples, operationIds, request bodies, response codes and
 * media types, required flags, minimum, integer types without format, plain "type: string" skuId, no
 * Idempotency-Key parameter.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SpecConformanceTest {

    private static final Path SPEC = Path.of("docs/NUULY-ASSESSMENT-README-JUL-2026.md");
    private static final String[] METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"};

    /** One allowed difference: the kind (MISSING, CHANGED or EXTRA), the pointers it covers and why. */
    private record Allowed(String kind, Pattern pointer, String decision, String reason) {
        Allowed(String kind, String pointerRegex, String decision, String reason) {
            this(kind, Pattern.compile(pointerRegex), decision, reason);
        }

        boolean covers(Difference difference) {
            return kind.equals(difference.kind) && pointer.matcher(difference.pointer).matches();
        }

        @Override
        public String toString() {
            return kind + " " + pointer.pattern() + " [" + decision + ": " + reason + "]";
        }
    }

    private record Difference(String kind, String pointer, Object spec, Object actual) {
        @Override
        public String toString() {
            return kind + " " + pointer + "\n    spec:   " + oneLine(spec) + "\n    actual: " + oneLine(actual);
        }
    }

    /** Pointers are "/paths/" + path + "/" + method + ..., so the path "/inventory" reads "/paths//inventory/get". */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("CHANGED", "/openapi", "H12", "the openapi version string (3.1.0, not 3.0.3)"),
            new Allowed("EXTRA", "/servers/\\d+/.+", "H12", "generated servers"),
            new Allowed("EXTRA", "/paths/.+/(get|post|put|delete)/tags/\\d+", "H12", "generated tags"),
            new Allowed("EXTRA", "/info/description", "H12, H4", "states the deviations and points to /v2"),
            new Allowed("CHANGED", "/paths//inventory/get/description", "H4, OD-5",
                    "GET /inventory description states the 250 cap and the after cursor"),
            new Allowed("CHANGED", "/paths//inventory/get/responses/200/description", "H4, OD-5",
                    "GET /inventory 200 description states the 250 cap and the after cursor"),
            new Allowed("EXTRA", "/paths//inventory/get/parameters/query:after/.+", "H4, OD-5",
                    "the after cursor parameter"),
            new Allowed("EXTRA", "/paths//inventory/get/responses/200/headers/Link/.+", "H4, OD-5",
                    "the Link header of the 200"),
            new Allowed("EXTRA", "/paths//inventory/get/responses/400/.+", "H4, Z3", "the 400 response"));

    @Autowired
    MockMvc mvc;

    // ---- parsing and normalizing ----

    private static String specYamlBlock() throws IOException {
        Matcher m = Pattern.compile("```ya?ml\\R(.*?)```", Pattern.DOTALL).matcher(Files.readString(SPEC, UTF_8));
        assertThat(m.find()).as("YAML block in the spec").isTrue();
        String block = m.group(1);
        assertThat(m.find()).as("exactly one YAML block in the spec").isFalse();
        return block;
    }

    /**
     * Pushes path-level parameters down into each operation, turns each operation's parameters into a map keyed
     * "in:name" (their order doesn't matter) and replaces {@code $ref: '#/components/schemas/Error'} by its
     * definition (the spec's Error schema; springdoc's export inlines it and has no Error component).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalize(Map<String, Object> doc) {
        Map<String, Object> components = (Map<String, Object>) doc.getOrDefault("components", Map.of());
        Map<String, Object> schemas = (Map<String, Object>) components.getOrDefault("schemas", Map.of());
        Map<String, Object> error = (Map<String, Object>) schemas.get("Error");
        for (Object pathItem : ((Map<String, Object>) doc.get("paths")).values()) {
            Map<String, Object> item = (Map<String, Object>) pathItem;
            List<Object> shared = (List<Object>) item.remove("parameters");
            for (String method : METHODS) {
                Map<String, Object> operation = (Map<String, Object>) item.get(method);
                if (operation == null) {
                    continue;
                }
                List<Object> merged = new ArrayList<>(shared == null ? List.of() : shared);
                merged.addAll((List<Object>) operation.getOrDefault("parameters", List.of()));
                Map<String, Object> byKey = new LinkedHashMap<>();
                for (Object p : merged) {
                    Map<String, Object> parameter = (Map<String, Object>) p;
                    byKey.put(parameter.get("in") + ":" + parameter.get("name"), parameter);
                }
                if (byKey.isEmpty()) {
                    operation.remove("parameters");
                } else {
                    operation.put("parameters", byKey);
                }
            }
        }
        return (Map<String, Object>) inline(doc, error);
    }

    @SuppressWarnings("unchecked")
    private static Object inline(Object node, Map<String, Object> error) {
        if (node instanceof Map<?, ?> m) {
            if ("#/components/schemas/Error".equals(m.get("$ref"))) {
                assertThat(error).as("components.schemas.Error, needed to inline its $ref").isNotNull();
                return new LinkedHashMap<>(error);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, inline(v, error)));
            return out;
        }
        if (node instanceof List<?> l) {
            return l.stream().map(v -> inline(v, error)).toList();
        }
        return node;
    }

    /** Flattens to "/a/b/c" to scalar (lists by index); strings are trimmed (the spec's folded scalars end in \n). */
    private static Map<String, Object> flatten(Map<String, Object> normalized) {
        Map<String, Object> flat = new TreeMap<>();
        walk("", normalized, flat);
        flat.keySet().removeIf(k -> k.startsWith("/components/schemas/Error")); // inlined everywhere
        return flat;
    }

    private static void walk(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> walk(prefix + "/" + k, v, out));
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                walk(prefix + "/" + i, l.get(i), out);
            }
        } else {
            out.put(prefix, node instanceof String str ? str.trim() : node);
        }
    }

    private static String oneLine(Object o) {
        return String.valueOf(o).replace("\n", "\\n");
    }

    private Map<String, Object> specFlat() throws IOException {
        Map<String, Object> flat = flatten(normalize(OpenApiDocs.parse(specYamlBlock())));
        assertThat(flat).as("parsed spec").containsKey("/paths//inventory/get/operationId");
        return flat;
    }

    private Map<String, Object> actualFlat() throws Exception {
        return flatten(normalize(OpenApiDocs.exported(mvc, V1)));
    }

    private List<Difference> differences() throws Exception {
        Map<String, Object> spec = specFlat();
        Map<String, Object> actual = actualFlat();
        List<Difference> differences = new ArrayList<>();
        for (String pointer : new TreeSet<>(spec.keySet())) {
            if (!actual.containsKey(pointer)) {
                differences.add(new Difference("MISSING", pointer, spec.get(pointer), null));
            } else if (!String.valueOf(spec.get(pointer)).equals(String.valueOf(actual.get(pointer)))) {
                differences.add(new Difference("CHANGED", pointer, spec.get(pointer), actual.get(pointer)));
            }
        }
        for (String pointer : new TreeSet<>(actual.keySet())) {
            if (!spec.containsKey(pointer)) {
                differences.add(new Difference("EXTRA", pointer, null, actual.get(pointer)));
            }
        }
        return differences;
    }

    // ---- the checks ----

    @Test
    void onlyTheAllowedDifferencesOccur() throws Exception {
        List<Difference> unexpected = differences().stream()
                .filter(difference -> ALLOWED.stream().noneMatch(rule -> rule.covers(difference)))
                .toList();

        assertThat(unexpected).as("differences from the spec that no decision allows (fix the export, not the test)")
                .withFailMessage(() -> unexpected.size() + " difference(s) from the spec's YAML that no decision allows:\n"
                        + unexpected.stream().map(Difference::toString).collect(Collectors.joining("\n")))
                .isEmpty();
    }

    @Test
    void everyAllowedDifferenceStillOccurs() throws Exception {
        List<Difference> differences = differences();
        List<Allowed> gone = ALLOWED.stream()
                .filter(rule -> differences.stream().noneMatch(rule::covers))
                .toList();

        assertThat(gone).withFailMessage(() -> "allowed differences that no longer occur (remove them from the table):\n"
                + gone.stream().map(Allowed::toString).collect(Collectors.joining("\n"))).isEmpty();
    }

    /** H12, H4: the allowed texts must say what the table claims. */
    @Test
    void allowedTextsStateTheDeviations() throws Exception {
        Map<String, Object> actual = actualFlat();

        assertThat(String.valueOf(actual.get("/info/description"))).as("info.description (H12, H4)")
                .contains("/v2", "250");
        assertThat(String.valueOf(actual.get("/paths//inventory/get/description")))
                .as("GET /inventory description (H4, OD-5)").contains("250", "after");
        assertThat(String.valueOf(actual.get("/paths//inventory/get/responses/200/description")))
                .as("GET /inventory 200 description (H4, OD-5)").contains("250", "after");
    }

    /** OD-5, H4: there is no limit parameter on the unversioned list (the page is fixed at 250; limit is /v2's). */
    @Test
    void unversionedListHasNoLimitParameter() throws Exception {
        assertThat(actualFlat().keySet()).noneMatch(pointer -> pointer.contains("query:limit"));
    }

    /** H3, OD-4, H12: no Idempotency-Key parameter on any unversioned operation; skuId is a plain string. */
    @Test
    void unversionedDocumentsNoIdempotencyKeyAndPlainSkuId() throws Exception {
        Map<String, Object> actual = actualFlat();

        assertThat(actual.keySet()).noneMatch(pointer -> pointer.contains("header:Idempotency-Key"));
        assertThat(actual.keySet().stream().filter(pointer -> pointer.contains("/parameters/path:skuId/schema/")))
                .containsExactlyInAnyOrder(
                        "/paths//inventory/{skuId}/get/parameters/path:skuId/schema/example",
                        "/paths//inventory/{skuId}/get/parameters/path:skuId/schema/type",
                        "/paths//inventory/{skuId}/post/parameters/path:skuId/schema/example",
                        "/paths//inventory/{skuId}/post/parameters/path:skuId/schema/type",
                        "/paths//inventory/{skuId}/purchase/post/parameters/path:skuId/schema/example",
                        "/paths//inventory/{skuId}/purchase/post/parameters/path:skuId/schema/type");
    }
}
