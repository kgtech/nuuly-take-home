package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import com.kgtech.inventoryapi.idempotency.IdempotencyStore;

/**
 * E1, G11, C3, G9, R4, R8, C2: the service's reads. find checks the skuId before any repository access and reads one
 * row; list parses limit leniently, cuts after at NUL and asks for one row more than the page. Neither opens a
 * transaction. Plain unit test: StockRepository and the transaction manager are mocks, and StockRepository is the
 * only path from the service to Postgres, so no interaction with it means no query.
 */
class InventoryServiceReadTest {

    /** The reads, with the one repository call each makes. */
    enum Read {
        FIND(service -> service.find("widget"), stock -> stock.find("widget")),
        LIST(service -> service.list(null, null), stock -> stock.page("", 251)),
        LIST_PAGE(service -> service.list("2", "B"), stock -> stock.page("B", 3));

        final Consumer<InventoryService> call;
        final Consumer<StockRepository> repositoryCall;

        Read(Consumer<InventoryService> call, Consumer<StockRepository> repositoryCall) {
            this.call = call;
            this.repositoryCall = repositoryCall;
        }
    }

    private final StockRepository stock = mock(StockRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final InventoryService service = new InventoryService(stock, mock(IdempotencyStore.class),
            mock(KeyedResponses.class), transactions);

    static Stream<String> findWithInvalidSkuIdReturnsEmptyWithoutRepositoryAccess() {
        return Stream.of("-bad", "a".repeat(65), "a b", "abc\n", "ABC-1;lot=7", "ABC-1;");
    }

    /** G11, S2, C3: the check runs before any database access, so no connection is borrowed. */
    @ParameterizedTest
    @NullSource
    @MethodSource
    void findWithInvalidSkuIdReturnsEmptyWithoutRepositoryAccess(String skuId) {
        assertThat(service.find(skuId)).isEmpty();
        verifyNoInteractions(stock, transactions);
    }

    @Test
    void findWithValidSkuIdQueriesRepository() {
        when(stock.find("widget")).thenReturn(Optional.of(new Balance(5, 3)));

        assertThat(service.find("widget")).isEqualTo(Optional.of(new InventoryItem("widget", 5)));
        verify(stock).find("widget");
    }

    /** E1, C3: each read is one autocommit repository query; no transaction is opened. */
    @ParameterizedTest
    @EnumSource(Read.class)
    void readsRunWithoutATransaction(Read read) {
        when(stock.find("widget")).thenReturn(Optional.of(new Balance(5, 1)));
        when(stock.page(anyString(), anyLong())).thenReturn(List.of());

        read.call.accept(service);

        read.repositoryCall.accept(verify(stock));
        verifyNoMoreInteractions(stock);
        verifyNoInteractions(transactions);
    }

    private static InventoryItem item(String skuId, long quantity) {
        return new InventoryItem(skuId, quantity);
    }

    private static List<InventoryItem> rows(int count) {
        List<InventoryItem> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(item(String.format("S%03d", i), i));
        }
        return rows;
    }

    /** G9, C2: no limit and no after asks for the default page of 250 plus one; items keep the row order. */
    @Test
    void listWithoutParamsQueriesDefaultPagePlusOne() {
        when(stock.page("", 251)).thenReturn(List.of(item("A", 1), item("b", Long.MAX_VALUE)));

        Page<InventoryItem> page = service.list(null, null);

        assertThat(page.items()).containsExactly(item("A", 1), item("b", Long.MAX_VALUE));
        assertThat(page.next()).isEmpty();
        verify(stock).page("", 251);
        verifyNoMoreInteractions(stock);
    }

    /**
     * G9, R4, C2: without limit, with or without after, a 251st row means a next page at the default size; the cursor
     * is the 250th row. No after queries from "".
     */
    @ParameterizedTest(name = "after={0} → query after {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "NULL | ''",
        "A    | A"
    })
    void listWithoutLimitSetsNextAtDefault(String after, String queried) {
        when(stock.page(queried, 251)).thenReturn(rows(251));

        Page<InventoryItem> page = service.list(null, after);

        assertThat(page.items()).isEqualTo(rows(250));
        assertThat(page.next()).contains(new Page.Next(250, "S250"));
    }

    /** C2: exactly 250 rows without limit is the last page. */
    @Test
    void listWithoutLimitExactly250HasNoNext() {
        when(stock.page("", 251)).thenReturn(rows(250));

        Page<InventoryItem> page = service.list(null, null);

        assertThat(page.items()).isEqualTo(rows(250));
        assertThat(page.next()).isEmpty();
    }

    /** G9: a limit of n asks for n + 1 rows after the cursor; no cursor starts before every sku_id. */
    @Test
    void listWithLimitQueriesLimitPlusOne() {
        service.list("2", null);

        verify(stock).page("", 3);
        verifyNoMoreInteractions(stock);
    }

    @Test
    void listWithLimitAndAfterQueriesAfterCursor() {
        service.list("2", "B-2");

        verify(stock).page("B-2", 3);
        verifyNoMoreInteractions(stock);
    }

    /**
     * R4: limit is ASCII digits with an optional sign. Non-positive, non-numeric, blank, padded, decimal, repeated and
     * non-ASCII-digit values are ignored, so the default page of 250 (plus the extra row) is asked for (C2).
     */
    @ParameterizedTest(name = "limit \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", " ", "abc", "0", "-1", "-0", "+0", "00", "+", "-", "1.5", "1e3", "0x10", " 5", "5 ",
        "2,3", "٣", "５", "٥٠", "-99999999999999999999"})
    void listIgnoresUnusableLimit(String limit) {
        when(stock.page("", 251)).thenReturn(List.of(item("A", 1)));

        Page<InventoryItem> page = service.list(limit, null);

        assertThat(page.items()).containsExactly(item("A", 1));
        assertThat(page.next()).isEmpty();
        verify(stock).page("", InventoryService.DEFAULT_LIMIT + 1L);
        verifyNoMoreInteractions(stock);
    }

    /** R4, R8: usable limits query limit + 1; an explicit plus sign is allowed; above 250, even past long, is 250. */
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
    void listUsesOrClampsLimit(String limit, long queried) {
        service.list(limit, null);

        verify(stock).page("", queried);
        verifyNoMoreInteractions(stock);
    }

    /** R4, C2: after alone (never validated) returns up to the default page of 250 after it. */
    @ParameterizedTest
    @ValueSource(strings = {"B-2", "", "zzz", "not a sku id!", "a+b&c=d", "A-1,B-2"})
    void listWithAfterOnlyUsesDefaultLimit(String after) {
        when(stock.page(after, 251)).thenReturn(rows(3));

        Page<InventoryItem> page = service.list(null, after);

        assertThat(page.items()).isEqualTo(rows(3));
        assertThat(page.next()).isEmpty();
        verify(stock).page(after, 251);
        verifyNoMoreInteractions(stock);
    }

    /** R4, C2: an ignored limit with after behaves as after alone. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "abc", ""})
    void listWithIgnoredLimitAndAfterUsesDefaultLimit(String limit) {
        when(stock.page("B-2", 251)).thenReturn(List.of());

        Page<InventoryItem> page = service.list(limit, "B-2");

        assertThat(page.next()).isEmpty();
        verify(stock).page("B-2", 251);
        verifyNoMoreInteractions(stock);
    }

    /** G9: the extra row only signals a next page; it is dropped and the cursor is the last row returned. */
    @Test
    void listSetsNextWhenExtraRowReturned() {
        when(stock.page("", 3)).thenReturn(rows(3));

        Page<InventoryItem> page = service.list("2", null);

        assertThat(page.items()).isEqualTo(rows(2));
        assertThat(page.next()).contains(new Page.Next(2, "S002"));
    }

    /** G9: exactly n rows left, or fewer, is the last page. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void listHasNoNextWithoutExtraRow(int returned) {
        when(stock.page("A", 3)).thenReturn(rows(returned));

        Page<InventoryItem> page = service.list("2", "A");

        assertThat(page.items()).isEqualTo(rows(returned));
        assertThat(page.next()).isEmpty();
    }

    /** R8: the next cursor carries the normalized limit, not the raw one. */
    @Test
    void listNextCarriesClampedLimit() {
        when(stock.page("", 251)).thenReturn(rows(251));

        Page<InventoryItem> page = service.list("9999", null);

        assertThat(page.items()).isEqualTo(rows(250));
        assertThat(page.next()).contains(new Page.Next(250, "S250"));
    }

    @Test
    void listNextCarriesSignFreeLimit() {
        when(stock.page("", 6)).thenReturn(rows(6));

        assertThat(service.list("+5", null).next()).contains(new Page.Next(5, "S005"));
    }

    static Stream<Arguments> listTruncatesAfterAtNul() {
        return Stream.of(
                Arguments.of("abc\0x", "abc"),
                Arguments.of("\0", ""),
                Arguments.of("a\0\0", "a"));
    }

    /** R4: after is cut at the first NUL (Postgres rejects NUL in text); every sku_id sorts above the cut. */
    @ParameterizedTest
    @MethodSource
    void listTruncatesAfterAtNul(String after, String truncated) {
        when(stock.page(anyString(), anyLong())).thenReturn(List.of());

        service.list("2", after);
        service.list(null, after);

        verify(stock).page(truncated, 3);
        verify(stock).page(truncated, 251);
        verifyNoMoreInteractions(stock);
    }
}
