package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * OQ5, G11, X1: the service reads run in a read-only transaction and {@code find} rejects a malformed skuId before
 * any repository access. No Docker and no Boot; the repository and transaction manager are mocks. SkuRepository is
 * the only path from the reads to Postgres, so no interaction with it means no query (NQ1).
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

    /** X1: stock writes run in the service's SERIALIZABLE TransactionTemplate, never under @Transactional. */
    @Test
    void writeMethodsHaveNoTransactionalAnnotation() throws Exception {
        assertThat(InventoryService.class.getMethod("add", String.class, int.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(InventoryService.class.getMethod("purchase", String.class, int.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }
}
