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
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.UriComponentsBuilder;

import com.kgtech.archfixture.inventory.FeatureFixtures;
import com.kgtech.archfixture.inventory.web.FeatureWebFixtures;
import com.kgtech.archfixture.web.WebFixtures;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore;
import com.kgtech.inventoryapi.idempotency.Operation;
import com.kgtech.inventoryapi.inventory.CreateSku;
import com.kgtech.inventoryapi.inventory.DetailsOutcome;
import com.kgtech.inventoryapi.inventory.InventoryService;
import com.kgtech.inventoryapi.inventory.KeyedResponses;
import com.kgtech.inventoryapi.inventory.Page;
import com.kgtech.inventoryapi.inventory.SkuCost;
import com.kgtech.inventoryapi.inventory.SkuDetails;
import com.kgtech.inventoryapi.inventory.StockOutcome;
import com.kgtech.inventoryapi.inventory.WriteResult;
import com.kgtech.inventoryapi.web.TextErrors;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * D10, Z2, A37, A38, A39: the package layout, checked on the compiled main classes with ArchUnit's core API. javac
 * inlines constants, so a constant-only dependency is invisible here; PackageBoundaryTest scans the sources for those
 * and for quoted names. Rules keep ArchUnit's default of failing when nothing matches, so none passes vacuously. The
 * placement rule's self-tests run it on fixture classes of their own (com.kgtech.archfixture, under src/test), which
 * the main-class import never contains.
 */
class ArchitectureTest {

    private static final String ROOT = "com.kgtech.inventoryapi";
    private static final String WEB = ROOT + ".web";
    private static final String IDEMPOTENCY = ROOT + ".idempotency";
    private static final String INVENTORY = ROOT + ".inventory";
    private static final String INVENTORY_WEB = INVENTORY + ".web";
    /** The fixture layout the placement rule's self-tests import (src/test, outside ROOT; see WebFixtures). */
    private static final String FIXTURE_ROOT = "com.kgtech.archfixture";
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
     * A37 (G10, S6, C1), R2-06: what serves every path lives in web, not in a feature: any controller advice
     * (meta-annotated, so a plain @ControllerAdvice too), an ErrorReportValve, a WebServerFactoryCustomizer, a
     * DispatcherServlet, the API-wide @OpenAPIDefinition, and a @Configuration that declares one of those or a
     * JsonMapperBuilderCustomizer as a @Bean. Any other @Configuration (a feature's Clock, say) may live in its
     * feature, under that package's dependency rules. @Configuration counts when direct, or when meta-annotated
     * outside the root package, where @SpringBootApplication (meta-annotated with it) sits.
     */
    @Test
    void appWideHttpClassesResideInWeb() {
        placementRule(ROOT).check(MAIN);
    }

    /** The placement rule for the layout rooted at {@code root}; the self-tests run it on fixtures of their own. */
    private static ArchRule placementRule(String root) {
        return classes().that(appWideHttp(root)).should().resideInAPackage(root + ".web");
    }

    private static DescribedPredicate<JavaClass> appWideHttp(String root) {
        return describe("are app-wide HTTP (a controller advice, an ErrorReportValve, a WebServerFactoryCustomizer, "
                        + "a DispatcherServlet or an @OpenAPIDefinition carrier, or a @Configuration declaring one of "
                        + "them or a JsonMapperBuilderCustomizer as a bean)",
                (JavaClass c) -> isAppWideHttpKind(c) || (isConfiguration(c, root) && declaresAppWideBean(c)));
    }

    private static boolean isAppWideHttpKind(JavaClass type) {
        return type.isMetaAnnotatedWith(ControllerAdvice.class)
                || type.isAssignableTo(ErrorReportValve.class)
                || type.isAssignableTo(WebServerFactoryCustomizer.class)
                || type.isAssignableTo(DispatcherServlet.class)
                || type.isMetaAnnotatedWith(OpenAPIDefinition.class);
    }

    /** Directly annotated, or meta-annotated outside the root package (where the @SpringBootApplication class is). */
    private static boolean isConfiguration(JavaClass type, String root) {
        return type.isAnnotatedWith(Configuration.class)
                || (type.isMetaAnnotatedWith(Configuration.class) && !type.getPackageName().equals(root));
    }

    private static boolean declaresAppWideBean(JavaClass configuration) {
        return configuration.getMethods().stream()
                .filter(method -> method.isAnnotatedWith(Bean.class))
                .map(JavaMethod::getRawReturnType)
                .anyMatch(bean -> isAppWideHttpKind(bean) || bean.isAssignableTo(JsonMapperBuilderCustomizer.class));
    }

    // ---- the placement rule's self-tests (R2-06): fixtures under src/test, never in MAIN ----

    /** The main-class import holds no test class, so the fixtures below can never reach the rules above. */
    @Test
    void mainImportHoldsNoTestClasses() {
        assertThat(MAIN.contain(ArchitectureTest.class)).as("MAIN contains ArchitectureTest").isFalse();
        assertThat(MAIN.contain(TestcontainersConfiguration.class)).as("MAIN contains TestcontainersConfiguration")
                .isFalse();
        assertThat(MAIN.stream()).as("MAIN classes under %s", FIXTURE_ROOT)
                .noneMatch(c -> c.getPackageName().startsWith(FIXTURE_ROOT));
    }

    /** App-wide HTTP in web passes, and is matched: rules fail when they match nothing. */
    @Test
    void placementRuleAcceptsAppWideHttpInWeb() {
        assertNoViolation(placementRule(FIXTURE_ROOT).evaluate(fixtures()));
    }

    /** R2-06: a feature's own @Configuration, here one declaring a Clock, may live in its feature package. */
    @Test
    void placementRuleLetsAFeatureOwnItsConfiguration() {
        assertNoViolation(placementRule(FIXTURE_ROOT)
                .evaluate(fixtures(FeatureFixtures.ClockConfiguration.class)));
    }

    /** R2-06: @SpringBootApplication is meta-annotated with @Configuration; the rule leaves the application alone. */
    @Test
    void placementRuleLeavesTheApplicationClassAlone() {
        assertNoViolation(placementRule(ROOT).allowEmptyShould(true)
                .evaluate(new ClassFileImporter().importClasses(InventoryApplication.class)));
    }

    static Stream<Class<?>> placementRuleKeepsAppWideHttpInWeb() {
        return Stream.of(
                FeatureFixtures.ContainerCustomizerConfiguration.class,
                FeatureFixtures.JsonConfiguration.class,
                FeatureFixtures.DispatcherAutoConfiguration.class,
                FeatureFixtures.ErrorAdvice.class,
                FeatureFixtures.ErrorPageValve.class,
                FeatureFixtures.ContainerCustomizer.class,
                FeatureFixtures.Dispatcher.class,
                FeatureFixtures.ApiDefinition.class,
                FeatureWebFixtures.ErrorPageConfiguration.class);
    }

    /**
     * R2-06: each app-wide HTTP kind outside web fails the rule, and so does a feature's @Configuration (direct or
     * meta-annotated) that declares one, or a JsonMapperBuilderCustomizer, as a bean. A feature's web package is not
     * web. Only the fixture is reported; web's own fixture passes beside it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void placementRuleKeepsAppWideHttpInWeb(Class<?> fixture) {
        EvaluationResult result = placementRule(FIXTURE_ROOT).evaluate(fixtures(fixture));
        assertThat(result.getFailureReport().getDetails()).as("violations with %s", fixture.getSimpleName())
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("<" + fixture.getName() + ">");
    }

    /**
     * D7, Z2: controllers belong to a feature's web package and nothing outside it calls them. Meta-annotated, so a
     * {@code @Controller} with {@code @ResponseBody} counts as well as a {@code @RestController}.
     */
    @Test
    void controllersArePackagePrivateInAFeatureWebPackage() {
        classes().that().areMetaAnnotatedWith(Controller.class)
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
     * D3, S1: SQL runs only through JdbcClient in the domain's @Repository classes and IdempotencyStore, so no other
     * class touches any JDBC type: not JdbcClient, JdbcTemplate or a DataSource utility (org.springframework.jdbc), and
     * not java.sql or javax.sql. The exemption is the annotation in the inventory package, not a name, so a
     * *Repository class elsewhere is still checked.
     */
    @Test
    void jdbcOnlyInRepositoriesAndIdempotencyStore() {
        DescribedPredicate<JavaClass> notDomainRepository = DescribedPredicate.not(
                        JavaClass.Predicates.resideInAPackage(INVENTORY)
                                .and(CanBeAnnotated.Predicates.annotatedWith(Repository.class)))
                .as("are not @Repository classes in %s", INVENTORY);
        noClasses().that(notDomainRepository)
                .and().doNotHaveFullyQualifiedName(IdempotencyStore.class.getName())
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .check(MAIN);
    }

    /**
     * A37 (L2): both list endpoints share one package-private paging helper for the next-page Link (C2) and the
     * repeated-after check (Z3), so the controllers neither build URLs nor read raw parameter values themselves.
     */
    @Test
    void nextLinkAndRepeatedAfterCheckLiveInOnePagingHelper() {
        CompositeArchRule.of(noClasses().that().areMetaAnnotatedWith(Controller.class)
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
     * constructor of a public web type must have a caller outside web; a helper only web uses stays package-private.
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
     * R1-05: TextErrors keeps only the generic text/plain helpers (of, textFor, invalidRequest, internalServerError),
     * so those are the only methods web may make public; with webPublicCodeIsUsedByAnotherPackage, each public one is
     * also called from another package. The inventory feature's answers (skuNotFound, insufficientInventory,
     * skuExists, detailsChanged) belong to inventory.web, which builds them through TextErrors.of.
     */
    @Test
    void webPublicMethodsAreTheGenericTextHelpers() {
        Set<String> methods = MAIN.stream()
                .filter(c -> c.getPackageName().equals(WEB))
                .filter(JavaClass::isTopLevelClass)
                .filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.getModifiers().contains(JavaModifier.PUBLIC))
                .map(m -> m.getOwner().getSimpleName() + "." + m.getName())
                .collect(Collectors.toSet());

        assertThat(methods).as("public methods of the public types of %s", WEB)
                .isNotEmpty()
                .isSubsetOf("TextErrors.of", "TextErrors.textFor", "TextErrors.invalidRequest",
                        "TextErrors.internalServerError");
    }

    /**
     * A37 (L1), R1-05: javac inlines constants, so bytecode shows no reader of a public field; the list is explicit
     * instead. The controllers read IDEMPOTENCY_KEY (Z2). The inventory feature's texts live in inventory.web,
     * SKU_EXISTS and DETAILS_CHANGED (the v2 409 and 412 descriptions InventoryApi reads) included; G6's generic
     * texts stay package-private.
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

    /**
     * S5, D6, R1-05: one Content-Type path. Every text/plain error response gets its Content-Type from TextErrors.of,
     * so no other class reads MediaType.TEXT_PLAIN; a feature's error helpers build their responses through
     * TextErrors.of. (MediaType.TEXT_PLAIN_VALUE is an inlined String, set directly on the servlet responses the valve
     * and the request guard write; bytecode does not show it.)
     */
    @Test
    void onlyTextErrorsReadsTheTextPlainMediaType() {
        CompositeArchRule.of(noClasses().that().doNotHaveFullyQualifiedName(TextErrors.class.getName())
                        .should().accessField(MediaType.class, "TEXT_PLAIN"))
                .and(classes().that().haveFullyQualifiedName(TextErrors.class.getName())
                        .should().accessField(MediaType.class, "TEXT_PLAIN"))
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

    /**
     * A38: an outcome is not a WriteResult; WriteResult wraps one, so each write names the family it returns. With
     * eachWriteReturnsWriteResultOfItsOwnOutcome this makes a case for another family's outcome a compile error (the
     * sealed families are disjoint), which is what removed the "not a ..." branches.
     */
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

    /**
     * A38: the spec list and the v2 list share one generic page type, Page of their item type. Nested types count
     * too, and a page is recognised by its cursor: a no-argument method returning Optional that is named next or
     * returns Optional of Page.Next.
     */
    @Test
    void inventoryHasOnePageType() {
        DescribedPredicate<JavaClass> pageType = describe(
                "have a next-page cursor (Optional next(), or any accessor returning Optional<Page.Next>)",
                c -> c.getMethods().stream().anyMatch(m -> m.getRawParameterTypes().isEmpty()
                        && m.getRawReturnType().isEquivalentTo(Optional.class)
                        && (m.getName().equals("next") || returnsOptionalOf(m, Page.Next.class))));
        classes().that().resideInAPackage(INVENTORY).and(pageType)
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

    /** Web's fixture plus {@code others}, imported on their own: never MAIN. */
    private static JavaClasses fixtures(Class<?>... others) {
        return new ClassFileImporter().importClasses(Stream.concat(Stream.of(WebFixtures.WebHttpConfiguration.class),
                Stream.of(others)).toList());
    }

    private static void assertNoViolation(EvaluationResult result) {
        assertThat(result.getFailureReport().getDetails()).as("violations").isEmpty();
    }

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

    /** Whether {@code method} returns {@code Optional<type>}, read from its generic signature. */
    private static boolean returnsOptionalOf(JavaMethod method, Class<?> type) {
        return method.getReturnType() instanceof JavaParameterizedType returned
                && returned.toErasure().isEquivalentTo(Optional.class)
                && returned.getActualTypeArguments().size() == 1
                && returned.getActualTypeArguments().getFirst().toErasure().isEquivalentTo(type);
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
