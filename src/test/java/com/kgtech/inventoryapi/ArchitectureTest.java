package com.kgtech.inventoryapi;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.conditions.ArchPredicates.are;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitReference;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
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

    /**
     * Package-private classes, so named by string. RoutedPath is the one parser of the request path (F-04).
     * InventoryErrorAdvice is listed for its log lines only, which print the raw method and path; it must not decide
     * anything from the raw path.
     */
    private static final Set<String> RAW_PATH_ALLOW_LIST = Set.of(
            ROOT + ".inventory.web.RoutedPath",
            ROOT + ".inventory.web.InventoryErrorAdvice");

    private static final String IDEMPOTENCY_PACKAGE = ROOT + ".idempotency";
    private static final String INVENTORY_SERVICE = ROOT + ".inventory.InventoryService";
    /** Package-private, so named by string: the unversioned (spec) controller and the /v2 controller. */
    private static final String UNVERSIONED_CONTROLLER = ROOT + ".inventory.web.InventoryController";
    private static final String V2_CONTROLLER = ROOT + ".inventory.web.SkuDetailsController";
    /** The service methods behind the spec's four operations; the keyed /v2 ones end in V2 (F-07). */
    private static final Set<String> UNVERSIONED_SERVICE_METHODS = Set.of("add", "purchase", "find", "list");

    private static JavaClasses main;
    private static JavaClasses tests;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(ROOT);
        tests = new ClassFileImporter().withImportOption(new ImportOption.OnlyIncludeTests()).importPackages(ROOT);
    }

    /**
     * L19: build Links and path checks from the routed path, never the raw URI. Only RoutedPath parses it; the guard
     * and accept filters ask RoutedPath. The error advice's log lines are the one other reader.
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

    /** L31, OD-4: the unversioned controller knows no idempotency type, so it can't read, replay or store a key. */
    @Test
    void unversionedControllerDoesNotDependOnTheIdempotencyPackage() {
        noClasses().that().haveFullyQualifiedName(UNVERSIONED_CONTROLLER)
                .should().dependOnClassesThat(resideInAnyPackage(IDEMPOTENCY_PACKAGE + ".."))
                .as("the unversioned InventoryController depends on no class in " + IDEMPOTENCY_PACKAGE)
                .check(main);
    }

    /** L31: the unversioned controller calls only the service methods of the spec's operations, never a keyed one. */
    @Test
    void unversionedControllerCallsOnlyTheUnversionedServiceMethods() {
        noClasses().that().haveFullyQualifiedName(UNVERSIONED_CONTROLLER)
                .should(callMethodWhere(describe("an InventoryService method that is not add, purchase, find or list "
                        + "(the keyed /v2 methods end in V2)", (JavaCall<?> call) ->
                        call.getTargetOwner().getName().equals(INVENTORY_SERVICE)
                                && !UNVERSIONED_SERVICE_METHODS.contains(call.getName()))))
                .as("the unversioned InventoryController calls InventoryService.add, purchase, find and list only")
                .check(main);
    }

    /** L31: the /v2 controller never calls the unversioned add, purchase, find or list. */
    @Test
    void v2ControllerDoesNotCallTheUnversionedServiceMethods() {
        noClasses().that().haveFullyQualifiedName(V2_CONTROLLER)
                .should(callMethodWhere(describe("an unversioned InventoryService method", (JavaCall<?> call) ->
                        call.getTargetOwner().getName().equals(INVENTORY_SERVICE)
                                && UNVERSIONED_SERVICE_METHODS.contains(call.getName()))))
                .as("SkuDetailsController calls no unversioned InventoryService method")
                .check(main);
    }

    /**
     * L31, invariant 5: the service methods behind the spec's operations, and every method of the service they call or
     * reference (calls, method references, constructor references), reach no type of the idempotency package. The keyed writes are addV2 and purchaseV2.
     */
    @Test
    void unversionedServiceMethodsReachNoIdempotencyType() {
        JavaClass service = main.get(INVENTORY_SERVICE);
        Deque<JavaMethod> todo = new ArrayDeque<>();
        service.getMethods().stream().filter(method -> isUnversioned(method.getName())).forEach(todo::add);
        assertThat(todo).as("the unversioned add, purchase, find and list are found").isNotEmpty();
        Set<JavaMethod> seen = new HashSet<>();
        List<String> violations = new ArrayList<>();
        while (!todo.isEmpty()) {
            JavaMethod method = todo.poll();
            if (!seen.add(method)) {
                continue;
            }
            method.getRawParameterTypes().forEach(type -> flagIdempotency(method, type, violations));
            flagIdempotency(method, method.getRawReturnType(), violations);
            // M-22: method and constructor references (InventoryService::ok, StoredResponse::new) are edges too
            List<JavaAccess<?>> edges = new ArrayList<>(method.getAccessesFromSelf());
            edges.addAll(method.getMethodReferencesFromSelf());
            edges.addAll(method.getConstructorReferencesFromSelf());
            for (JavaAccess<?> access : edges) {
                flagIdempotency(method, access.getTargetOwner(), violations);
                if (access instanceof JavaFieldAccess field) {
                    flagIdempotency(method, field.getTarget().getRawType(), violations);
                }
                if (access.getTargetOwner().equals(service)) {
                    if (access instanceof JavaCall<?> call) {
                        followMethod(call.getTarget().resolveMember(), todo);
                    } else if (access instanceof JavaCodeUnitReference<?> reference) {
                        followMethod(reference.getTarget().resolveMember(), todo);
                    }
                }
            }
        }
        assertThat(violations).as("unversioned service methods reaching the idempotency package").isEmpty();
    }

    private static void followMethod(Optional<? extends JavaMember> member, Deque<JavaMethod> todo) {
        member.filter(JavaMethod.class::isInstance).map(JavaMethod.class::cast).ifPresent(todo::add);
    }

    /** add, purchase, find, list and the synthetic lambda$add$0-style methods compiled from their lambdas. */
    private static boolean isUnversioned(String methodName) {
        if (UNVERSIONED_SERVICE_METHODS.contains(methodName)) {
            return true;
        }
        String[] parts = methodName.split("\\$");
        return parts.length == 3 && parts[0].equals("lambda") && UNVERSIONED_SERVICE_METHODS.contains(parts[1]);
    }

    private static void flagIdempotency(JavaMethod method, JavaClass type, List<String> violations) {
        if (type.getPackageName().startsWith(IDEMPOTENCY_PACKAGE)) {
            violations.add(method.getFullName() + " -> " + type.getName());
        }
    }
}
