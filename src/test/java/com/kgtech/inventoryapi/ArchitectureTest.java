package com.kgtech.inventoryapi;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.equalTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
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

import com.kgtech.inventoryapi.idempotency.IdempotencyStore;
import com.kgtech.inventoryapi.idempotency.Operation;
import com.kgtech.inventoryapi.inventory.CreateSku;
import com.kgtech.inventoryapi.inventory.DetailsOutcome;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.KeyedResponses;
import com.kgtech.inventoryapi.inventory.SkuCost;
import com.kgtech.inventoryapi.inventory.SkuDetails;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * D10, Z2, A37, A38, A39: the package layout, checked on the compiled main classes with ArchUnit's core API. javac
 * inlines constants, so a constant-only dependency is invisible here; PackageBoundaryTest scans the sources for those
 * and for quoted names. Rules keep ArchUnit's default of failing when nothing matches, so none passes vacuously.
 */
class ArchitectureTest {

    private static final String ROOT = "com.kgtech.inventoryapi";
    private static final String WEB = ROOT + ".web";
    private static final String IDEMPOTENCY = ROOT + ".idempotency";
    private static final String INVENTORY = ROOT + ".inventory";
    private static final String INVENTORY_WEB = INVENTORY + ".web";
    /** The records that are also the wire schema (D7, C2): the only domain types with OpenAPI annotations. */
    private static final String WIRE_RECORDS = "(InventoryItem|SkuItem|SkuDetails|SkuCost|CreateSku)";
    /** The records whose JSON needs Jackson annotations today (absent values omitted, the ETag version hidden). */
    private static final String JACKSON_RECORDS = "(SkuItem|SkuDetails)";

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

    /** Z2, A39: Jackson annotations only on the records that use them today; no Jackson API in the domain at all. */
    @Test
    void jacksonAnnotationsInTheDomainOnlyWhereUsedToday() {
        CompositeArchRule.of(noClasses().that().resideInAPackage(INVENTORY)
                        .and().haveNameNotMatching(regex(INVENTORY, JACKSON_RECORDS))
                        .should().dependOnClassesThat()
                        .resideInAnyPackage("com.fasterxml.jackson..", "tools.jackson.."))
                .and(noClasses().that().resideInAPackage(INVENTORY)
                        .should().dependOnClassesThat().resideInAPackage("tools.jackson..")
                        .orShould().dependOnClassesThat(
                                resideIn("com.fasterxml.jackson..", "com.fasterxml.jackson.annotation..")))
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

    /** D3, S1: SQL runs only through JdbcClient in the repositories and IdempotencyStore. */
    @Test
    void jdbcClientOnlyInRepositoriesAndIdempotencyStore() {
        noClasses().that().haveSimpleNameNotEndingWith("Repository")
                .and().doNotHaveFullyQualifiedName(IdempotencyStore.class.getName())
                .should().dependOnClassesThat()
                .haveNameMatching("org\\.springframework\\.jdbc\\.core\\.simple\\.JdbcClient(\\$.*)?")
                .check(MAIN);
    }

    /**
     * A37 (L2): both list endpoints share one package-private paging helper for the next-page Link (C2) and the
     * repeated-after check (Z3), so the controllers neither build URLs nor read raw parameter values themselves.
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
                .containsExactlyInAnyOrder("HttpConstants", "TextErrors", "BodyTooLargeException");
    }

    @Test
    void idempotencyPublicTypesAreTheCrossPackageContract() {
        assertThat(publicTopLevelTypes(IDEMPOTENCY)).as("public top-level types of %s", IDEMPOTENCY)
                .containsExactlyInAnyOrder("IdempotencyKey", "IdempotencyStore", "Operation", "StoredResponse");
    }

    /** A38: one Page type replaces InventoryPage and SkuPage; everything else is what inventory.web uses. */
    @Test
    void inventoryPublicTypesAreTheCrossPackageContract() {
        assertThat(publicTopLevelTypes(INVENTORY)).as("public top-level types of %s", INVENTORY)
                .containsExactlyInAnyOrder("CreateSku", "DetailsOutcome", "DetailsPrecondition", "InventoryItem",
                        "InventoryService", "KeyedResponses", "Page", "ReplaceResult", "SkuCost", "SkuDetails",
                        "SkuId", "SkuItem", "StockOutcome", "WriteResult");
    }

    /**
     * A37 (L1): TextErrors and BodyTooLargeException are public for inventory.web, so every public method or
     * constructor of a public web type must have a caller outside web; the helpers only web uses (of, textFor,
     * internalServerError) stay package-private. Constant fields are inlined, so PackageBoundaryTest's scope, not this.
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

    /** L4: members used only inside their own class or package are not public. */
    @Test
    void membersUsedOnlyInsideTheirClassOrPackageAreNotPublic() {
        CompositeArchRule.of(fields().that().areDeclaredIn(SkuDetails.class)
                        .and().haveNameMatching("MAX_(NAME|DESCRIPTION|IMAGES|URL)")
                        .should().bePrivate())
                .and(fields().that().areDeclaredIn(SkuCost.class).and().haveName("CURRENCY_REGEX")
                        .should().bePrivate())
                .and(methods().that().areDeclaredIn(Operation.class).and().haveName("dbValue")
                        .should().bePackagePrivate())
                .check(MAIN);
    }

    // ---- result and page types (A38) ----

    /** A38: an outcome is not a WriteResult; WriteResult wraps one, so each write names the family it returns. */
    @Test
    void outcomesAreNotWriteResults() {
        classes().that().areAssignableTo(StockOutcome.class).or().areAssignableTo(DetailsOutcome.class)
                .should().notBeAssignableTo(WriteResult.class)
                .check(MAIN);
    }

    /** A38: add, purchase and create each return WriteResult of their own outcome type, never the other API's. */
    @Test
    void eachWriteReturnsWriteResultOfItsOwnOutcome() throws NoSuchMethodException {
        SoftAssertions softly = new SoftAssertions();
        assertReturnsWriteResultOf(softly, InventoryService.class.getMethod("add", String.class, int.class,
                String.class), StockOutcome.Add.class);
        assertReturnsWriteResultOf(softly, InventoryService.class.getMethod("purchase", String.class, int.class,
                String.class), StockOutcome.Purchase.class);
        assertReturnsWriteResultOf(softly, InventoryService.class.getMethod("create", String.class, CreateSku.class,
                String.class), DetailsOutcome.class);
        softly.assertAll();
    }

    /** A38: KeyedResponses renders each outcome family through its own typed method, none through WriteResult. */
    @Test
    void keyedResponsesRendersEachFamilyThroughATypedMethod() {
        List<Method> renderers = Stream.of(KeyedResponses.class.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .toList();

        assertThat(renderers).as("KeyedResponses methods taking the wide WriteResult")
                .noneMatch(m -> Stream.of(m.getParameterTypes()).anyMatch(WriteResult.class::isAssignableFrom));
        assertThat(renderers).as("KeyedResponses method for stock outcomes")
                .anyMatch(m -> Stream.of(m.getParameterTypes()).anyMatch(StockOutcome.class::isAssignableFrom));
        assertThat(renderers).as("KeyedResponses method for details outcomes")
                .anyMatch(m -> Stream.of(m.getParameterTypes()).anyMatch(DetailsOutcome.class::isAssignableFrom));
    }

    /** A38: each outcome switch in the web layer is exhaustive over its own type; none throws for the other API's. */
    @Test
    void webLayerHasNoUnreachableOutcomeBranch() {
        noClasses().that().resideInAPackage(INVENTORY_WEB)
                .should().callConstructorWhere(describe("a target that is an IllegalStateException",
                        (JavaConstructorCall call) ->
                                call.getTargetOwner().isEquivalentTo(IllegalStateException.class)))
                .check(MAIN);
    }

    /** A38: the spec list and the v2 list share one generic page type, Page of their item type. */
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
