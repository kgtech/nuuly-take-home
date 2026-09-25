package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.kgtech.inventoryapi.idempotency.Idempotent;
import com.kgtech.inventoryapi.idempotency.Operation;

/**
 * OQ5, G11, S2, X1, Z1: the service reads run in a read-only transaction, and {@code find} and the writes reject a
 * malformed skuId before any repository or transaction access. No Docker and no Boot, so no retry or @Idempotent
 * advice; the repository and transaction manager are mocks. SkuRepository is the only path from the service to
 * Postgres, so no interaction with it means no query (NQ1).
 */
@SpringJUnitConfig(InventoryServiceReadTest.Config.class)
class InventoryServiceReadTest {

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        InventoryService inventoryService(SkuRepository skus, PlatformTransactionManager tm) {
            return new InventoryService(skus, tm);
        }
    }

    /** The two reads, with the repository call each one must make inside the transaction. */
    enum Read {
        FIND(service -> service.find("widget"), skus -> skus.findQuantity("widget")),
        FIND_ALL(InventoryService::findAll, SkuRepository::findAllQuantities);

        final Consumer<InventoryService> call;
        final Consumer<SkuRepository> repositoryCall;

        Read(Consumer<InventoryService> call, Consumer<SkuRepository> repositoryCall) {
            this.call = call;
            this.repositoryCall = repositoryCall;
        }
    }

    @MockitoBean
    SkuRepository skus;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    InventoryService service;

    @BeforeEach
    void stubTransactionManager() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static SkuQuantity row(String skuId, long quantity) {
        return new SkuQuantity() {
            @Override
            public String getSkuId() {
                return skuId;
            }

            @Override
            public long getQuantity() {
                return quantity;
            }
        };
    }

    static Stream<String> findWithInvalidSkuIdReturnsEmptyWithoutRepositoryAccess() {
        return Stream.of("-bad", "a".repeat(65), "a b", "abc\n");
    }

    @ParameterizedTest
    @NullSource
    @MethodSource
    void findWithInvalidSkuIdReturnsEmptyWithoutRepositoryAccess(String skuId) {
        assertThat(service.find(skuId)).isEmpty();
        verifyNoInteractions(skus);
    }

    @Test
    void findWithValidSkuIdQueriesRepository() {
        when(skus.findQuantity("widget")).thenReturn(Optional.of(5L));

        assertThat(service.find("widget")).isEqualTo(Optional.of(new InventoryItem("widget", 5)));
        verify(skus).findQuantity("widget");
    }

    @Test
    void findAllMapsProjectionsInOrder() {
        when(skus.findAllQuantities()).thenReturn(List.of(row("A", 1), row("b", Long.MAX_VALUE)));

        assertThat(service.findAll())
                .containsExactly(new InventoryItem("A", 1), new InventoryItem("b", Long.MAX_VALUE));
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void readMethodsRunInReadOnlyTransaction(Read read) {
        when(skus.findQuantity("widget")).thenReturn(Optional.of(5L));
        when(skus.findAllQuantities()).thenReturn(List.of());

        read.call.accept(service);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        verify(transactionManager).commit(any());

        InOrder order = inOrder(transactionManager, skus);
        order.verify(transactionManager).getTransaction(any());
        read.repositoryCall.accept(order.verify(skus));
        order.verify(transactionManager).commit(any());
    }

    static Stream<Arguments> writeWithMalformedSkuIdReturnsOutcomeWithoutRepositoryOrTransaction() {
        List<Arguments> cases = new ArrayList<>();
        for (String skuId : Arrays.asList(null, "-bad", "a".repeat(65), "a b", "abc\n")) {
            for (String key : Arrays.asList(null, "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b", "nope")) {
                cases.add(Arguments.of(skuId, key));
            }
        }
        return cases.stream();
    }

    /** S2, Z1: the service checks the raw skuId whatever the key; create → InvalidRequest, purchase → NotFound. */
    @ParameterizedTest
    @MethodSource
    void writeWithMalformedSkuIdReturnsOutcomeWithoutRepositoryOrTransaction(String skuId, String key) {
        assertThat(service.add(skuId, 5, key)).isEqualTo(new WriteResult.InvalidRequest());
        assertThat(service.purchase(skuId, 5, key)).isEqualTo(new StockOutcome.NotFound());

        verifyNoInteractions(skus, transactionManager);
    }

    /** Z1: a valid skuId reaches the repository inside the service's SERIALIZABLE REQUIRED transaction. */
    @Test
    void writeWithValidSkuIdRunsInSerializableRequiredTransaction() {
        when(skus.add("widget", 5)).thenReturn(new StockOutcome.Ok(5));

        assertThat(service.add("widget", 5, null)).isEqualTo(new StockOutcome.Ok(5));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verify(skus).add("widget", 5);
        verify(transactionManager).commit(any());
    }

    /**
     * X1, Y2, Z1: each stock write is the single (String, int, String) method, carries @Idempotent with its operation
     * and the W2 @Retryable, and has no @Transactional.
     */
    @Test
    void writeMethodsAreIdempotentRetryableAndNotTransactional() throws Exception {
        for (Operation operation : Operation.values()) {
            String name = operation.dbValue();
            Method write = InventoryService.class.getMethod(name, String.class, int.class, String.class);

            assertThat(write.getAnnotation(Idempotent.class)).as(name).isNotNull()
                    .extracting(Idempotent::value).isEqualTo(operation);
            Retryable retryable = write.getAnnotation(Retryable.class);
            assertThat(retryable).as(name).isNotNull();
            assertThat(retryable.includes()).as(name).containsExactly(PessimisticLockingFailureException.class);
            assertThat(retryable.predicate()).as(name).isEqualTo(SerializationFailure.class);
            assertThat(retryable.maxRetries()).as(name).isEqualTo(10);
            assertThat(retryable.delay()).as(name).isEqualTo(5);
            assertThat(retryable.jitter()).as(name).isEqualTo(5);
            assertThat(retryable.multiplier()).as(name).isEqualTo(2);
            assertThat(retryable.maxDelay()).as(name).isEqualTo(200);
            assertThat(write.isAnnotationPresent(Transactional.class)).as(name).isFalse();
            assertThat(Arrays.stream(InventoryService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name))).as(name + " overloads").hasSize(1);
        }
    }
}
