package com.kgtech.inventoryapi;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.conditions.ArchPredicates.are;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import jakarta.servlet.http.HttpServletRequest;

/**
 * H15: architecture guards that hold on the real layout (no Spring context). The domain and idempotency package
 * boundaries stay in {@link com.kgtech.inventoryapi.inventory.PackageBoundaryTest}. Rules use the ArchUnit core API,
 * not the JUnit engine, so they run as plain JUnit 5 tests.
 */
class ArchitectureTest {

    private static final String ROOT = "com.kgtech.inventoryapi";

    /** Package-private classes, so named by string. */
    private static final Set<String> RAW_PATH_ALLOW_LIST = Set.of(
            ROOT + ".inventory.web.InventoryErrorAdvice",
            ROOT + ".inventory.web.InventoryRequestGuardFilter",
            ROOT + ".inventory.web.JsonAcceptForGetFilter");

    private static JavaClasses main;
    private static JavaClasses tests;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(ROOT);
        tests = new ClassFileImporter().withImportOption(new ImportOption.OnlyIncludeTests()).importPackages(ROOT);
    }

    /**
     * L19: build Links and path checks from the routed path, never the raw URI. The allow-list is exactly the classes
     * that read the raw path today; the RoutedPath helper (F-04) replaces it with one class.
     */
    @Test
    void rawRequestPathIsReadOnlyByTheAllowListedClasses() {
        noClasses().that(are(describe("not on the raw-path allow-list", c -> !RAW_PATH_ALLOW_LIST.contains(c.getName()))))
                .should(callMethodWhere(
                        describe("a HttpServletRequest raw-path getter", (JavaCall<?> call) ->
                                call.getTargetOwner().isEquivalentTo(HttpServletRequest.class)
                                        && Set.of("getRequestURI", "getRequestURL", "getServletPath")
                                                .contains(call.getName()))))
                .as("no main class outside the allow-list calls HttpServletRequest.getRequestURI/getRequestURL/getServletPath")
                .check(main);
    }

    /** L27: one Boot test annotation, one container. */
    @Test
    void onlyIntegrationTestIsAnnotatedWithSpringBootTest() {
        noClasses().that().areNotAssignableTo(IntegrationTest.class)
                .should().beAnnotatedWith(SpringBootTest.class)
                .as("test classes use @IntegrationTest; only IntegrationTest itself carries @SpringBootTest")
                .check(tests);
    }

    @Test
    void onlyTestcontainersConfigurationTouchesTestcontainers() {
        noClasses().that().areNotAssignableTo(TestcontainersConfiguration.class)
                .should().dependOnClassesThat(resideInAnyPackage("org.testcontainers..").and(
                        describe("a container type", c -> c.getSimpleName().endsWith("Container")
                                || c.getSimpleName().equals("Testcontainers"))))
                .as("only TestcontainersConfiguration references org.testcontainers container types")
                .allowEmptyShould(true)
                .check(tests);
    }

    /** E2: Postgres through JdbcClient; no JPA, Hibernate or Spring Data in main code. */
    @Test
    void mainCodeDoesNotUsePersistenceFrameworks() {
        noClasses().should().dependOnClassesThat(resideInAnyPackage("jakarta.persistence..",
                        "org.springframework.data..", "org.hibernate.."))
                .as("no main class depends on jakarta.persistence, org.springframework.data or org.hibernate")
                .check(main);
    }
}
