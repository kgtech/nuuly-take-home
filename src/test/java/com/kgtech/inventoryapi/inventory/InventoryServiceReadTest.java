package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * OQ5, G11, S2, X1, Z1, G9, R4: the service reads run in a read-only transaction, list parses limit leniently, and {@code find} and the writes reject a
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

    /** The reads, with the repository call each one must make inside the transaction. */
    enum Read {
        FIND(service -> service.find("widget"), skus -> skus.findQuantity("widget")),
        LIST(service -> service.list(null, null), SkuRepository::findAllQuantities),
        LIST_PAGE(service -> service.list("2", "B"), skus -> skus.findQuantitiesAfter("B", 3));

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

    private static List<SkuQuantity> rows(int count) {
        List<SkuQuantity> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(row(String.format("S%03d", i), i));
        }
        return rows;
    }

    private static List<InventoryItem> items(List<SkuQuantity> rows) {
        return rows.stream().map(r -> new InventoryItem(r.getSkuId(), r.getQuantity())).toList();
    }

    /** G9, OQ5: no limit and no after keeps the unpaged query; the items are the projections in order. */
    @Test
    void listWithoutParamsUsesFindAllQuantities() {
        when(skus.findAllQuantities()).thenReturn(List.of(row("A", 1), row("b", Long.MAX_VALUE)));

        InventoryPage page = service.list(null, null);

        assertThat(page.items())
                .containsExactly(new InventoryItem("A", 1), new InventoryItem("b", Long.MAX_VALUE));
        assertThat(page.next()).isEmpty();
        verify(skus).findAllQuantities();
        verifyNoMoreInteractions(skus);
    }

    /** G9: a limit of n asks for n + 1 rows after the cursor; no cursor starts before every sku_id. */
    @Test
    void listWithLimitQueriesLimitPlusOne() {
        service.list("2", null);

        verify(skus).findQuantitiesAfter("", 3);
        verifyNoMoreInteractions(skus);
    }

    @Test
    void listWithLimitAndAfterQueriesAfterCursor() {
        service.list("2", "B-2");

        verify(skus).findQuantitiesAfter("B-2", 3);
        verifyNoMoreInteractions(skus);
    }

    /**
     * R4, OQ3: limit is ASCII digits with an optional sign. Non-positive, non-numeric, blank, padded, decimal,
     * repeated and non-ASCII-digit values are ignored: the whole table, from the unpaged query.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "abc", "0", "-1", "-0", "+0", "00", "+", "-", "1.5", "1e3", "0x10", " 5", "5 ",
        "2,3", "\u0663", "\uff15", "\u0665\u0660", "-99999999999999999999"})
    void listIgnoresUnusableLimit(String limit) {
        when(skus.findAllQuantities()).thenReturn(List.of(row("A", 1)));

        InventoryPage page = service.list(limit, null);

        assertThat(page.items()).containsExactly(new InventoryItem("A", 1));
        assertThat(page.next()).isEmpty();
        verify(skus).findAllQuantities();
        verifyNoMoreInteractions(skus);
    }

    /** R4, R8, OQ3: usable limits query limit + 1; anything above 250, even past int or long, is 250. */
    @ParameterizedTest(name = "limit={0} → query {1}")
    @CsvSource(delimiter = '|', value = {
        "1                     | 2",
        "+5                    | 6",
        "007                   | 8",
        "249                   | 250",
        "250                   | 251",
        "251                   | 251",
        "9999                  | 251",
        "2147483647            | 251",
        "2147483648            | 251",
        "99999999999           | 251",
        "9223372036854775808   | 251",
        "99999999999999999999  | 251"
    })
    void listQueriesNormalizedLimitPlusOne(String limit, long queried) {
        service.list(limit, null);

        verify(skus).findQuantitiesAfter("", queried);
        verifyNoMoreInteractions(skus);
    }

    /** R4, AC4: after alone returns every row after it (unbounded, never validated) and never a next cursor. */
    @ParameterizedTest
    @ValueSource(strings = {"B-2", "", "zzz", "not a sku id!", "a+b&c=d"})
    void listWithAfterOnlyIsUnbounded(String after) {
        when(skus.findQuantitiesAfter(after, Long.MAX_VALUE)).thenReturn(rows(3));

        InventoryPage page = service.list(null, after);

        assertThat(page.items()).isEqualTo(items(rows(3)));
        assertThat(page.next()).isEmpty();
        verify(skus).findQuantitiesAfter(after, Long.MAX_VALUE);
        verifyNoMoreInteractions(skus);
    }

    /** R4: an ignored limit with after behaves as after alone. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "abc", ""})
    void listWithIgnoredLimitAndAfterIsUnbounded(String limit) {
        InventoryPage page = service.list(limit, "B-2");

        assertThat(page.next()).isEmpty();
        verify(skus).findQuantitiesAfter("B-2", Long.MAX_VALUE);
        verifyNoMoreInteractions(skus);
    }

    /** G9: the extra row only signals a next page; it is dropped and the cursor is the last row returned. */
    @Test
    void listSetsNextWhenExtraRowReturned() {
        when(skus.findQuantitiesAfter("", 3)).thenReturn(rows(3));

        InventoryPage page = service.list("2", null);

        assertThat(page.items()).isEqualTo(items(rows(2)));
        assertThat(page.next()).contains(new InventoryPage.Next(2, "S002"));
    }

    /** G9: exactly n rows left, or fewer, is the last page. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void listHasNoNextWithoutExtraRow(int returned) {
        when(skus.findQuantitiesAfter("A", 3)).thenReturn(rows(returned));

        InventoryPage page = service.list("2", "A");

        assertThat(page.items()).isEqualTo(items(rows(returned)));
        assertThat(page.next()).isEmpty();
    }

    /** R8: the next cursor carries the normalized limit, not the raw one. */
    @Test
    void listNextCarriesClampedLimit() {
        when(skus.findQuantitiesAfter("", 251)).thenReturn(rows(251));

        InventoryPage page = service.list("9999", null);

        assertThat(page.items()).isEqualTo(items(rows(250)));
        assertThat(page.next()).contains(new InventoryPage.Next(250, "S250"));
    }

    @Test
    void listNextCarriesSignFreeLimit() {
        when(skus.findQuantitiesAfter("", 6)).thenReturn(rows(6));

        assertThat(service.list("+5", null).next()).contains(new InventoryPage.Next(5, "S005"));
    }

    static Stream<Arguments> listTruncatesAfterAtNul() {
        return Stream.of(
                Arguments.of("abc\0x", "abc"),
                Arguments.of("\0", ""),
                Arguments.of("a\0\0", "a"));
    }

    /** OQ2: after is cut at the first NUL (Postgres rejects NUL in text); every sku_id sorts above the cut. */
    @ParameterizedTest
    @MethodSource
    void listTruncatesAfterAtNul(String after, String truncated) {
        service.list("2", after);
        service.list(null, after);

        verify(skus).findQuantitiesAfter(truncated, 3);
        verify(skus).findQuantitiesAfter(truncated, Long.MAX_VALUE);
        verifyNoMoreInteractions(skus);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void readMethodsRunInReadOnlyTransaction(Read read) {
        when(skus.findQuantity("widget")).thenReturn(Optional.of(5L));
        when(skus.findAllQuantities()).thenReturn(List.of());
        when(skus.findQuantitiesAfter(any(), anyLong())).thenReturn(List.of());

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
