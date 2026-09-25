package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Z1, S3, U3, S2, X1, R2, Y4: the @Idempotent advice on its own. A test target is proxied with the advisor from
 * IdempotencyConfiguration; the store and transaction manager are mocks and the IdempotentResults is a recording
 * fake. No Docker and no Boot. The Postgres behaviour of the same chain is in IdempotencyWiringTest and
 * IdempotencyApiIntegrationTest.
 */
class IdempotencyInterceptorTest {

    private static final String KEY = "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b";

    /** The target's result type; label says which path produced it. */
    record Result(String label) {
    }

    /** Recording IdempotentResults: rejects skuIds starting with "-", renders "skuId|label" as the stored body. */
    static final class Results implements IdempotentResults<Result> {

        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<Result> beforeClaim(Operation operation, String skuId) {
            calls.add("beforeClaim " + operation + " " + skuId);
            return skuId.startsWith("-") ? Optional.of(new Result("rejected " + operation)) : Optional.empty();
        }

        @Override
        public StoredResponse toStored(String skuId, Result result) {
            calls.add("toStored " + skuId + " " + result.label());
            return new StoredResponse(200, "text/plain", skuId + "|" + result.label());
        }

        @Override
        public Result stored(StoredResponse response) {
            calls.add("stored " + response.body());
            return new Result("stored " + response.status() + " " + response.body());
        }

        @Override
        public Result invalidRequest() {
            calls.add("invalidRequest");
            return new Result("invalid");
        }
    }

    /** The advised target: two @Idempotent methods in the required (String, int, String) shape. */
    static class Target {

        final List<String> calls = new ArrayList<>();
        RuntimeException failure;

        @Idempotent(Operation.ADD)
        public Result add(String skuId, int quantity, String idempotencyKey) {
            return call("add", skuId, quantity);
        }

        @Idempotent(Operation.PURCHASE)
        public Result purchase(String skuId, int quantity, String idempotencyKey) {
            return call("purchase", skuId, quantity);
        }

        /** Not annotated: never advised. */
        public Result plain(String skuId, int quantity, String idempotencyKey) {
            return call("plain", skuId, quantity);
        }

        private Result call(String name, String skuId, int quantity) {
            calls.add(name + " " + skuId + " " + quantity);
            if (failure != null) {
                throw failure;
            }
            return new Result(name + " " + skuId + " " + quantity);
        }
    }

    /** @Idempotent on a method that is not (String, int, String). */
    static class WrongSignature {

        @Idempotent(Operation.ADD)
        public Result add(String skuId, long quantity, String idempotencyKey) {
            return new Result("never");
        }
    }

    private IdempotencyStore store;
    private PlatformTransactionManager transactionManager;
    private Results results;
    private Target target;
    private Target proxy;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        results = new Results();

        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("idempotencyStore", store);
        beans.registerSingleton("transactionManager", transactionManager);
        beans.registerSingleton("results", results);
        Advisor advisor = IdempotencyConfiguration.idempotentAdvisor(beans.getBeanProvider(IdempotencyStore.class),
                beans.getBeanProvider(PlatformTransactionManager.class), beans);

        target = new Target();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        proxy = (Target) factory.getProxy();
    }

    /** The store runs the action once and reports Executed, as a first keyed request does. */
    private void storeExecutes() {
        when(store.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<StoredResponse> action = invocation.getArgument(1);
            return new KeyedResult.Executed(action.get());
        });
    }

    private void assertNoTransactionOrStore() {
        verifyNoInteractions(store, transactionManager);
    }

    // ---- G8: no key ----

    @Test
    void absentKeyProceedsWithoutTransactionOrStore() {
        assertThat(proxy.add("widget", 5, null)).isEqualTo(new Result("add widget 5"));
        assertThat(proxy.purchase("-bad", 3, null)).isEqualTo(new Result("purchase -bad 3"));

        assertThat(target.calls).containsExactly("add widget 5", "purchase -bad 3");
        assertThat(results.calls).as("no beforeClaim without a key; the method checks skuId itself").isEmpty();
        assertNoTransactionOrStore();
    }

    @Test
    void methodWithoutAnnotationIsNotAdvised() {
        assertThat(proxy.plain("widget", 5, "nope")).isEqualTo(new Result("plain widget 5"));

        assertThat(results.calls).isEmpty();
        assertNoTransactionOrStore();
    }

    // ---- S3: malformed key ----

    static Stream<String> malformedKeys() {
        return Stream.of("", "nope", "1-1-1-1-1", KEY + "," + UUID.randomUUID(), KEY.replace("-", ""),
                "{" + KEY + "}", KEY + " ");
    }

    @ParameterizedTest
    @MethodSource("malformedKeys")
    void malformedKeyReturnsInvalidRequestBeforeAnything(String key) {
        assertThat(proxy.add("widget", 5, key)).isEqualTo(new Result("invalid"));
        assertThat(proxy.purchase("widget", 5, key)).isEqualTo(new Result("invalid"));

        assertThat(target.calls).isEmpty();
        assertThat(results.calls).containsExactly("invalidRequest", "invalidRequest");
        assertNoTransactionOrStore();
    }

    /** U3: the key check runs before the skuId check. */
    @Test
    void badKeyWinsOverBeforeClaimRejection() {
        assertThat(proxy.purchase("-bad", 5, "nope")).isEqualTo(new Result("invalid"));

        assertThat(results.calls).containsExactly("invalidRequest");
        assertThat(target.calls).isEmpty();
        assertNoTransactionOrStore();
    }

    // ---- S2, U3: beforeClaim ----

    @ParameterizedTest
    @EnumSource(Operation.class)
    void beforeClaimRejectionIsReturnedWithoutTransactionOrStore(Operation operation) {
        Result result = operation == Operation.ADD
                ? proxy.add("-bad", 5, KEY)
                : proxy.purchase("-bad", 5, KEY);

        assertThat(result).isEqualTo(new Result("rejected " + operation));
        assertThat(results.calls).containsExactly("beforeClaim " + operation + " -bad");
        assertThat(target.calls).isEmpty();
        assertNoTransactionOrStore();
    }

    // ---- X1, R2: the keyed transaction ----

    @Test
    void validKeyOpensSerializableRequiresNewTransaction() {
        storeExecutes();

        proxy.add("widget", 5, KEY);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        InOrder order = inOrder(transactionManager, store);
        order.verify(transactionManager).getTransaction(any());
        order.verify(store).execute(any(), any());
        order.verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void requestCarriesParsedKeyOperationSkuIdQuantity(Operation operation) {
        storeExecutes();

        if (operation == Operation.ADD) {
            proxy.add("AbC-1", 7, KEY.toUpperCase());
        } else {
            proxy.purchase("AbC-1", 7, KEY.toUpperCase());
        }

        ArgumentCaptor<IdempotentRequest> request = ArgumentCaptor.forClass(IdempotentRequest.class);
        verify(store).execute(request.capture(), any());
        assertThat(request.getValue())
                .isEqualTo(new IdempotentRequest(UUID.fromString(KEY), operation, "AbC-1", 7));
    }

    /** Y4: the first response is rendered with the raw skuId inside the claim's action and returned via stored. */
    @Test
    void executedMapsToStoredWithSkuIdPassedToToStored() {
        storeExecutes();

        Result result = proxy.add("widget", 5, KEY);

        assertThat(target.calls).containsExactly("add widget 5");
        assertThat(results.calls).containsExactly(
                "beforeClaim ADD widget", "toStored widget add widget 5", "stored widget|add widget 5");
        assertThat(result).isEqualTo(new Result("stored 200 widget|add widget 5"));
    }

    @Test
    void replayedMapsToStoredWithoutCallingTarget() {
        StoredResponse response = new StoredResponse(404, "text/plain", "SKU not found");
        when(store.execute(any(), any())).thenReturn(new KeyedResult.Replayed(response));

        Result result = proxy.purchase("widget", 5, KEY);

        assertThat(result).isEqualTo(new Result("stored 404 SKU not found"));
        assertThat(target.calls).isEmpty();
        assertThat(results.calls).containsExactly("beforeClaim PURCHASE widget", "stored SKU not found");
    }

    /** S8, T1: a reused key with another request, or an expired key, is 400 "Invalid request". */
    @Test
    void rejectedMapsToInvalidRequest() {
        when(store.execute(any(), any())).thenReturn(new KeyedResult.Rejected());

        assertThat(proxy.add("widget", 5, KEY)).isEqualTo(new Result("invalid"));

        assertThat(target.calls).isEmpty();
        assertThat(results.calls).containsExactly("beforeClaim ADD widget", "invalidRequest");
    }

    /** A failure inside the action (e.g. 40001) escapes unchanged, for the retry outside, and rolls the claim back. */
    @Test
    void targetExceptionPropagatesAndRollsBack() {
        storeExecutes();
        IllegalStateException failure = new IllegalStateException("boom");
        target.failure = failure;

        assertThatThrownBy(() -> proxy.add("widget", 5, KEY)).isSameAs(failure);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(results.calls).containsExactly("beforeClaim ADD widget");
    }

    // ---- Z1: the pointcut ----

    @Test
    void pointcutMatchesOnlyIdempotentMethods() throws Exception {
        IdempotentMethodPointcut pointcut = new IdempotentMethodPointcut();
        Method add = Target.class.getMethod("add", String.class, int.class, String.class);
        Method plain = Target.class.getMethod("plain", String.class, int.class, String.class);

        assertThat(pointcut.matches(add, Target.class)).isTrue();
        assertThat(pointcut.matches(plain, Target.class)).isFalse();
    }

    @Test
    void wrongSignatureFailsAtProxyCreation() throws Exception {
        Method add = WrongSignature.class.getMethod("add", String.class, long.class, String.class);

        assertThatThrownBy(() -> new IdempotentMethodPointcut().matches(add, WrongSignature.class))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AopUtils.canApply(new IdempotentMethodPointcut(), WrongSignature.class))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> {
            try (var context = new AnnotationConfigApplicationContext(WrongSignatureConfig.class)) {
                context.getBean(WrongSignature.class);
            }
        }).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(IdempotencyConfiguration.class)
    static class WrongSignatureConfig {

        @Bean
        static DefaultAdvisorAutoProxyCreator autoProxyCreator() {
            DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
            creator.setProxyTargetClass(true);
            return creator;
        }

        @Bean
        WrongSignature wrongSignature() {
            return new WrongSignature();
        }
    }
}
