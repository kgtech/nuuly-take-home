package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.kgtech.inventoryapi.idempotency.IdempotencyStore;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore.Keyed;
import com.kgtech.inventoryapi.idempotency.Operation;
import com.kgtech.inventoryapi.idempotency.StoredResponse;

/**
 * G8, S3, G11, S2, U3, A33, A34: the checks InventoryService runs on a write before any I/O, and how it hands a keyed
 * write to IdempotencyStore. The repository, the store, the renderer and the transaction manager are mocks, so no
 * Docker and no Boot. The store's claim, replay, mismatch, expiry, rollback and transaction are IdempotencyStoreTest's;
 * the same chain over HTTP and Postgres is IdempotencyApiIntegrationTest's.
 */
class InventoryServiceWriteChecksTest {

    private static final String KEY = "3f2b8c1e-9a4d-4e7f-b6a0-1c2d3e4f5a6b";
    private static final StoredResponse STORED = new StoredResponse(200, "application/json", "{\"stored\":true}");

    /**
     * The two keyed writes, each with the answer a malformed skuId gets (G11: only purchase is 404) and its unkeyed
     * result from the stubs in setUp.
     */
    enum Write {
        ADD(Operation.ADD, new WriteResult.InvalidRequest<>(), new WriteResult.Done<>(new StockOutcome.Ok(12))),
        PURCHASE(Operation.PURCHASE, new WriteResult.Done<>(new StockOutcome.NotFound()),
                new WriteResult.Done<>(new StockOutcome.Ok(3)));

        final Operation operation;
        final WriteResult<?> malformedSkuId;
        final WriteResult<?> unkeyed;

        Write(Operation operation, WriteResult<?> malformedSkuId, WriteResult<?> unkeyed) {
            this.operation = operation;
            this.malformedSkuId = malformedSkuId;
            this.unkeyed = unkeyed;
        }

        WriteResult<?> call(InventoryService service, String skuId, String key) {
            return switch (this) {
                case ADD -> service.add(skuId, 7, key);
                case PURCHASE -> service.purchase(skuId, 7, key);
            };
        }
    }

    private final StockRepository stock = mock(StockRepository.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final KeyedResponses responses = mock(KeyedResponses.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final InventoryService service = new InventoryService(stock, idempotency, responses, transactions);

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(stock.add("widget", 7)).thenReturn(Optional.of(new Balance(12, 2)));
        when(stock.purchase("widget", 7)).thenReturn(new StockOutcome.Ok(3));
        when(responses.toStored(any(), any(StockOutcome.class))).thenReturn(STORED);
    }

    /** The store claims and runs the write once, as for a first keyed request. */
    private void storeRunsTheWrite() {
        when(idempotency.run(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<StoredResponse> write = invocation.getArgument(4);
            return new Keyed.Response(write.get());
        });
    }

    private void assertNothingTouched() {
        verifyNoInteractions(stock, idempotency, responses, transactions);
    }

    // ---- G8: no key ----

    /** No key: the service runs the write in its own READ COMMITTED transaction and never touches the store. */
    @ParameterizedTest
    @EnumSource(Write.class)
    void absentKeyRunsTheWriteInTheServicesTransaction(Write write) {
        WriteResult<?> result = write.call(service, "widget", null);

        assertThat(result).isEqualTo(write.unkeyed);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verifyNoInteractions(idempotency, responses);
    }

    // ---- S3: malformed key, never stored ----

    /** A present empty key and one non-UUID per write; the full format matrix is IdempotencyKeyTest's. */
    static Stream<Arguments> malformedKeys() {
        return Stream.of(Write.values()).flatMap(write -> Stream.of("", "nope").map(key -> Arguments.of(write, key)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("malformedKeys")
    void malformedKeyIsInvalidRequestBeforeAnything(Write write, String key) {
        assertThat(write.call(service, "widget", key)).isEqualTo(new WriteResult.InvalidRequest<>());

        assertNothingTouched();
    }

    /** U3, A34: the key is checked before the skuId, so a bad key wins with 400 over purchase's 404. */
    @Test
    void badKeyWinsOverBadSkuId() {
        assertThat(service.purchase("-bad", 7, "nope")).isEqualTo(new WriteResult.InvalidRequest<>());

        assertNothingTouched();
    }

    // ---- G11, S2, C3: malformed skuId, with or without a key, never stored ----

    /**
     * Per write and key state: null, pattern failures (leading '-', 65 characters, a space, a trailing newline) and the
     * raw segment with ";" content (C3); the full pattern matrix is SkuIdTest's.
     */
    static Stream<Arguments> malformedSkuIds() {
        return Stream.of(Write.values()).flatMap(write -> Stream.of(null, KEY)
                .flatMap(key -> Arrays.asList(null, "-a", "a".repeat(65), "a b", "abc\n", "ABC-1;lot=7", "ABC-1;")
                        .stream()
                        .map(skuId -> Arguments.of(write, key, skuId))));
    }

    @ParameterizedTest(name = "{0} key={1} skuId={2}")
    @MethodSource("malformedSkuIds")
    void malformedSkuIdGetsItsOperationsAnswerBeforeAnyIo(Write write, String key, String skuId) {
        assertThat(write.call(service, skuId, key)).isEqualTo(write.malformedSkuId);

        assertNothingTouched();
    }

    // ---- A33: a valid key hands the write to the store ----

    /** The store gets the parsed key, the operation, the raw skuId and the canonical request (Y3). */
    @ParameterizedTest
    @EnumSource(Write.class)
    void validKeyPassesTheRequestToTheStore(Write write) {
        storeRunsTheWrite();

        write.call(service, "widget", KEY.toUpperCase());

        verify(idempotency).run(eq(UUID.fromString(KEY)), eq(write.operation), eq("widget"), eq("7"), any());
        verifyNoInteractions(transactions);
    }

    /** Y4: a first run renders the outcome with the raw skuId inside the store's transaction; it comes back Stored. */
    @Test
    void firstRunStoresTheRenderedOutcome() {
        storeRunsTheWrite();

        assertThat(service.add("widget", 7, KEY)).isEqualTo(new WriteResult.Stored<>(STORED));

        verify(stock).add("widget", 7);
        verify(responses).toStored("widget", new StockOutcome.Ok(12));
    }

    /** A replay answers the stored response without running the write. */
    @Test
    void replayReturnsTheStoredResponseWithoutWriting() {
        StoredResponse notFound = new StoredResponse(404, "text/plain", "SKU not found");
        when(idempotency.run(any(), any(), any(), any(), any())).thenReturn(new Keyed.Response(notFound));

        assertThat(service.purchase("widget", 7, KEY)).isEqualTo(new WriteResult.Stored<>(notFound));

        verifyNoInteractions(stock, responses, transactions);
    }

    /** S8, T1, A18: a reused key with another request, an expired key or a row with no response is 400. */
    @Test
    void invalidReuseIsInvalidRequest() {
        when(idempotency.run(any(), any(), any(), any(), any())).thenReturn(new Keyed.Invalid());

        assertThat(service.add("widget", 7, KEY)).isEqualTo(new WriteResult.InvalidRequest<>());

        verifyNoInteractions(stock, responses, transactions);
    }

    /** A failure inside the write escapes unchanged, so the store's transaction rolls the claim back (a 500). */
    @Test
    void writeFailurePropagatesUnchanged() {
        storeRunsTheWrite();
        IllegalStateException failure = new IllegalStateException("boom");
        when(stock.purchase("widget", 7)).thenThrow(failure);

        assertThatThrownBy(() -> service.purchase("widget", 7, KEY)).isSameAs(failure);

        verifyNoInteractions(responses);
    }
}
