package com.kgtech.inventoryapi;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.equalTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;

import org.apache.catalina.valves.ErrorReportValve;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.UriComponentsBuilder;

import com.kgtech.inventoryapi.idempotency.Operation;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.KeyedResponses;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * D10, Z2, E1, E2, A37, A38, A39: the package layout and the persistence stack, checked on the compiled main classes
 * with ArchUnit's core API. javac inlines constants, so a constant-only dependency is invisible here;
 * PackageBoundaryTest scans the sources for those and for quoted names. Rules keep ArchUnit's default of failing when
 * nothing matches, so none passes vacuously. Types that are package-private today are named, not referenced.
 */
class ArchitectureTest {

    private static final String ROOT = "com.kgtech.inventoryapi";
    private static final String WEB = ROOT + ".web";
    private static final String IDEMPOTENCY = ROOT + ".idempotency";
    private static final String INVENTORY = ROOT + ".inventory";
    private static final String INVENTORY_WEB = INVENTORY + ".web";
    /** The record that is also the wire schema (D7, C2): the only domain type with OpenAPI annotations (A37). */
    private static final String WIRE_RECORDS = "(InventoryItem)";
    private static final String IDEMPOTENCY_STORE = IDEMPOTENCY + ".IdempotencyStore";
    private static final String INVENTORY_CONTROLLER = INVENTORY_WEB + ".InventoryController";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    // ---- dependency direction ----

    @Test
    void packagesHaveNoCycles() {
        slices().matching(ROOT + ".(**)").should().beFreeOfCycles().check(MAIN);
    }

    /** D10, A37: the shared packages never depend on the feature; the feature depends on them. */
    @Test
    void sharedPackagesDoNotDependOnFeatures() {
        CompositeArchRule.of(noClasses().that().resideInAPackage(WEB + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(INVENTORY + "..", IDEMPOTENCY + ".."))
                .and(noClasses().that().resideInAPackage(IDEMPOTENCY + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(INVENTORY + "..", WEB + ".."))
                .check(MAIN);
    }

    /** Z2: the domain (inventory itself, not inventory.web) knows no web layer and no HTTP, servlet or Tomcat type. */
    @Test
    void domainDoesNotDependOnWebOrTransport() {
        noClasses().that().resideInAPackage(INVENTORY)
                .should().dependOnClassesThat().resideInAnyPackage(INVENTORY_WEB + "..", WEB + "..",
                        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                        "org.apache.catalina..", "org.apache.tomcat..")
                .check(MAIN);
    }

    /** D7, C2, A39: the wire records carry OpenAPI annotations (openapi.yaml comes from them); nothing else does. */
    @Test
    void openApiAnnotationsInTheDomainOnlyOnWireRecords() {
        CompositeArchRule.of(noClasses().that().resideInAPackage(INVENTORY)
                        .and().haveNameNotMatching(regex(INVENTORY, WIRE_RECORDS))
                        .should().dependOnClassesThat().resideInAPackage("io.swagger.."))
                .and(noClasses().that().resideInAPackage(INVENTORY)
                        .should().dependOnClassesThat(resideIn("io.swagger..", "io.swagger.v3.oas.annotations..")))
                .check(MAIN);
    }

    /** Z2, A39: no domain record needs a Jackson annotation, and the domain uses no Jackson API. */
    @Test
    void noJacksonInTheDomain() {
        noClasses().that().resideInAPackage(INVENTORY)
                .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..", "tools.jackson..")
                .check(MAIN);
    }

    /** Issue #15 (R3-1), Z2, A39: StoredResponse and the rest of idempotency stay free of HTTP and servlet types. */
    @Test
    void idempotencyUsesNoHttpOrServletTypes() {
        noClasses().that().resideInAPackage(IDEMPOTENCY + "..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..",
                        "org.springframework.http..", "jakarta.servlet..", "org.apache.catalina..",
                        "org.apache.tomcat..")
                .check(MAIN);
    }

    // ---- placement ----

    /**
     * A37 (G10, S6, C1): the error advice, the Tomcat valve, the container customizer, the DispatcherServlet override
     * and every @Configuration apply to the whole application, so they live in web, not in the feature.
     */
    @Test
    void appWideHttpClassesResideInWeb() {
        classes().that().areAnnotatedWith(RestControllerAdvice.class)
                .or().areAssignableTo(ErrorReportValve.class)
                .or().areAssignableTo(WebServerFactoryCustomizer.class)
                .or().areAssignableTo(DispatcherServlet.class)
                .or().areAnnotatedWith(Configuration.class)
                .should().resideInAPackage(WEB)
                .check(MAIN);
    }

    /** A37: the API-wide OpenAPI info describes every controller, so it sits in web, not on one controller. */
    @Test
    void apiDefinitionResidesInWeb() {
        classes().that().areAnnotatedWith(OpenAPIDefinition.class)
                .should().resideInAPackage(WEB)
                .check(MAIN);
    }

    /** D7, Z2: controllers belong to a feature's web package and nothing outside it calls them. */
    @Test
    void restControllersArePackagePrivateInAFeatureWebPackage() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage(ROOT + ".*.web")
                .andShould().bePackagePrivate()
                .check(MAIN);
    }

    /** D10: repositories are package-private and reached only from the domain package (S1). */
    @Test
    void repositoriesArePackagePrivateAndUsedOnlyInInventory() {
        classes().that().areAnnotatedWith(Repository.class)
                .should().bePackagePrivate()
                .andShould().onlyHaveDependentClassesThat().resideInAPackage(INVENTORY)
                .check(MAIN);
    }

    /**
     * D3, S1: SQL runs only through JdbcClient in the domain's @Repository classes and IdempotencyStore. The exemption
     * is the annotation in the inventory package, not a name, so a *Repository class elsewhere is still checked.
     */
    @Test
    void jdbcClientOnlyInRepositoriesAndIdempotencyStore() {
        DescribedPredicate<JavaClass> notDomainRepository = DescribedPredicate.not(
                        JavaClass.Predicates.resideInAPackage(INVENTORY)
                                .and(CanBeAnnotated.Predicates.annotatedWith(Repository.class)))
                .as("are not @Repository classes in %s", INVENTORY);
        noClasses().that(notDomainRepository)
                .and().doNotHaveFullyQualifiedName(IDEMPOTENCY_STORE)
                .should().dependOnClassesThat()
                .haveNameMatching("org\\.springframework\\.jdbc\\.core\\.simple\\.JdbcClient(\\$.*)?")
                .check(MAIN);
    }

    /**
     * A37 (L2): one package-private paging helper builds the next-page Link (C2) and checks for a repeated after (Z3),
     * so the controller neither builds URLs nor reads raw parameter values itself.
     */
    @Test
    void nextLinkAndRepeatedAfterCheckLiveInOnePagingHelper() {
        CompositeArchRule.of(noClasses().that().areAnnotatedWith(RestController.class)
                        .should().dependOnClassesThat().areAssignableTo(UriComponentsBuilder.class)
                        .orShould().callMethodWhere(describe("a target named getParameterValues",
                                (JavaMethodCall call) -> call.getTarget().getName().equals("getParameterValues"))))
                .and(classes().that().resideInAPackage(INVENTORY_WEB)
                        .and(describe("build a URL with UriComponentsBuilder", (JavaClass c) ->
                                c.getDirectDependenciesFromSelf().stream().anyMatch(d ->
                                        d.getTargetClass().isAssignableTo(UriComponentsBuilder.class))))
                        .should().containNumberOfElements(equalTo(1)))
                .check(MAIN);
    }

    // ---- visibility: public means another package uses it ----

    /** Z2: nothing outside the feature reaches its web layer. */
    @Test
    void inventoryWebTypesArePackagePrivate() {
        classes().that().resideInAPackage(INVENTORY_WEB)
                .should().notBePublic()
                .andShould().notBeProtected()
                .check(MAIN);
    }

    /** A37: web's public types are the ones inventory.web uses; the advice, valve and configurations stay private. */
    @Test
    void webPublicTypesAreTheCrossPackageContract() {
        assertThat(publicTopLevelTypes(WEB)).as("public top-level types of %s", WEB)
                .containsExactlyInAnyOrder("HttpConstants", "TextErrors");
    }

    @Test
    void idempotencyPublicTypesAreTheCrossPackageContract() {
        assertThat(publicTopLevelTypes(IDEMPOTENCY)).as("public top-level types of %s", IDEMPOTENCY)
                .containsExactlyInAnyOrder("IdempotencyKey", "IdempotencyStore", "Operation", "StoredResponse");
    }

    /** A38: one Page type replaces InventoryPage; everything else is what inventory.web uses. */
    @Test
    void inventoryPublicTypesAreTheCrossPackageContract() {
        assertThat(publicTopLevelTypes(INVENTORY)).as("public top-level types of %s", INVENTORY)
                .containsExactlyInAnyOrder("InventoryItem", "InventoryService", "KeyedResponses", "Page", "SkuId",
                        "StockOutcome", "WriteResult");
    }

    /**
     * A37 (L1): TextErrors is public for inventory.web, so every public method or constructor of a public web type must
     * have a caller outside web; the helpers only web uses (of, textFor, internalServerError) stay package-private.
     * Constant fields are inlined, so webPublicFieldsAreTheSharedConstants lists them instead.
     */
    @Test
    void webPublicCodeIsUsedByAnotherPackage() {
        codeUnits().that().areDeclaredInClassesThat().resideInAPackage(WEB)
                .and().areDeclaredInClassesThat().arePublic()
                .and().areDeclaredInClassesThat().areTopLevelClasses()
                .and().arePublic()
                .should(beAccessedFromAnotherPackage())
                .check(MAIN);
    }

    /**
     * A37 (L1): javac inlines constants, so bytecode shows no reader of a public field; the list is explicit instead.
     * The controller reads IDEMPOTENCY_KEY (Z2); G6's texts stay package-private.
     */
    @Test
    void webPublicFieldsAreTheSharedConstants() {
        Set<String> fields = MAIN.stream()
                .filter(c -> c.getPackageName().equals(WEB))
                .filter(JavaClass::isTopLevelClass)
                .filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
                .flatMap(c -> c.getFields().stream())
                .filter(f -> f.getModifiers().contains(JavaModifier.PUBLIC))
                .map(f -> f.getOwner().getSimpleName() + "." + f.getName())
                .collect(Collectors.toSet());

        assertThat(fields).as("public fields of the public types of %s", WEB)
                .containsExactlyInAnyOrder("HttpConstants.IDEMPOTENCY_KEY");
    }

    /** L4, A37: members used only inside their own class or package are not public. */
    @Test
    void membersUsedOnlyInsideTheirClassOrPackageAreNotPublic() {
        methods().that().areDeclaredIn(Operation.class).and().haveName("dbValue")
                .should().bePackagePrivate()
                .check(MAIN);
    }

    // ---- result and page types (A38) ----

    /**
     * A38: an outcome is not a WriteResult; WriteResult wraps one, so each write names the family it returns. With
     * eachWriteReturnsWriteResultOfItsOwnOutcome this makes a case for another family's outcome a compile error (the
     * sealed families are disjoint), which is what removed the "not a ..." branches.
     */
    @Test
    void outcomesAreNotWriteResults() {
        classes().that().areAssignableTo(StockOutcome.class)
                .should().notBeAssignableTo(WriteResult.class)
                .check(MAIN);
    }

    /** A38: add and purchase each return WriteResult of their own outcome family. */
    @Test
    void eachWriteReturnsWriteResultOfItsOwnOutcome() throws NoSuchMethodException {
        SoftAssertions softly = new SoftAssertions();
        assertReturnsWriteResultOf(softly, InventoryService.class.getMethod("add", String.class, int.class,
                String.class), StockOutcome.Add.class);
        assertReturnsWriteResultOf(softly, InventoryService.class.getMethod("purchase", String.class, int.class,
                String.class), StockOutcome.Purchase.class);
        softly.assertAll();
    }

    /** A33, A38: KeyedResponses renders the stock outcomes through a typed method, not through WriteResult. */
    @Test
    void keyedResponsesRendersEachFamilyThroughATypedMethod() {
        List<Method> renderers = Stream.of(KeyedResponses.class.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .toList();

        assertThat(renderers).as("KeyedResponses methods taking the wide WriteResult")
                .noneMatch(m -> Stream.of(m.getParameterTypes()).anyMatch(WriteResult.class::isAssignableFrom));
        assertThat(renderers).as("KeyedResponses method for stock outcomes")
                .anyMatch(m -> Stream.of(m.getParameterTypes()).anyMatch(StockOutcome.class::isAssignableFrom));
    }

    /**
     * A38 (OQ-3 B, #28 C-36): the controller renders every write result through OutcomeResponses and StoredResponses,
     * so it names no single stock outcome and has no second switch over them.
     */
    @Test
    void controllerRendersOutcomesOnlyThroughOutcomeResponses() {
        noClasses().that().haveFullyQualifiedName(INVENTORY_CONTROLLER)
                .should().dependOnClassesThat().belongToAnyOf(StockOutcome.Ok.class, StockOutcome.NotFound.class,
                        StockOutcome.Insufficient.class, StockOutcome.Overflow.class)
                .check(MAIN);
    }

    /** A38: the list returns one generic page type, Page of its item type. */
    @Test
    void inventoryHasOnePageType() {
        DescribedPredicate<JavaClass> pageType = describe("have a next-page cursor (Optional next())",
                c -> c.getMethods().stream().anyMatch(m -> m.getName().equals("next")
                        && m.getRawParameterTypes().isEmpty()
                        && m.getRawReturnType().isEquivalentTo(Optional.class)));
        classes().that().resideInAPackage(INVENTORY).and().areTopLevelClasses().and(pageType)
                .should().containNumberOfElements(equalTo(1))
                .andShould(new ArchCondition<JavaClass>("be generic in the item type") {
                    @Override
                    public void check(JavaClass page, ConditionEvents events) {
                        boolean generic = page.getTypeParameters().size() == 1;
                        events.add(new SimpleConditionEvent(page, generic,
                                page.getName() + (generic ? " is" : " is not") + " generic in its item type"));
                    }
                })
                .check(MAIN);
    }

    // ---- persistence stack and concurrency (E1, E2) ----

    /** E1, Y2: nothing retries, so no main class uses Spring's resilience support (@Retryable, retry predicates). */
    @Test
    void noRetryFramework() {
        noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework.resilience..")
                .check(MAIN);
    }

    /** E2: SQL runs through JdbcClient only; no JPA, Spring Data or Hibernate ORM (Hibernate Validator stays, D1). */
    @Test
    void noJpaInMain() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
                .orShould().dependOnClassesThat(resideIn("org.hibernate..", "org.hibernate.validator.."))
                .check(MAIN);
    }

    // ---- helpers ----

    private static Set<String> publicTopLevelTypes(String packageName) {
        Set<String> types = MAIN.stream()
                .filter(c -> c.getPackageName().equals(packageName))
                .filter(JavaClass::isTopLevelClass)
                .filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());
        assertThat(MAIN.stream().filter(c -> c.getPackageName().equals(packageName)))
                .as("classes imported from %s", packageName).isNotEmpty();
        return types;
    }

    /** A regex for any of {@code simpleNames} (an alternation) in {@code packageName}, nested types included. */
    private static String regex(String packageName, String simpleNames) {
        return packageName.replace(".", "\\.") + "\\." + simpleNames + "(\\$.*)?";
    }

    /** Classes in {@code packageIdentifier} outside its {@code allowedIdentifier} sub-package. */
    private static DescribedPredicate<JavaClass> resideIn(String packageIdentifier, String allowedIdentifier) {
        return JavaClass.Predicates.resideInAPackage(packageIdentifier)
                .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage(allowedIdentifier)))
                .as("reside in %s except %s", packageIdentifier, allowedIdentifier);
    }

    private static ArchCondition<JavaCodeUnit> beAccessedFromAnotherPackage() {
        return new ArchCondition<>("be accessed from another package") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                String own = unit.getOwner().getPackageName();
                boolean outside = unit.getAccessesToSelf().stream()
                        .anyMatch(access -> !access.getOriginOwner().getPackageName().equals(own));
                events.add(new SimpleConditionEvent(unit, outside, unit.getFullName()
                        + (outside ? " is used outside " : " is public but used only inside ") + own));
            }
        };
    }

    private static void assertReturnsWriteResultOf(SoftAssertions softly, Method write, Class<?> outcome) {
        softly.assertThat(write.getGenericReturnType())
                .as("InventoryService.%s return type", write.getName())
                .isInstanceOfSatisfying(ParameterizedType.class, returned -> {
                    softly.assertThat(returned.getRawType()).as("%s raw type", write.getName())
                            .isEqualTo(WriteResult.class);
                    softly.assertThat(returned.getActualTypeArguments()).as("%s outcome type", write.getName())
                            .containsExactly(outcome);
                });
    }
}
