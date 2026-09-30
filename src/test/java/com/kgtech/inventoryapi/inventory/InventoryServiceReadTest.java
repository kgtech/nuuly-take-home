package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.transaction.PlatformTransactionManager;

import com.kgtech.inventoryapi.idempotency.IdempotencyStore;


/**
 * R4, R8, C2, H4, H5: the unversioned list has a fixed page of 250 and reads only after (cut at NUL); the /v2 list
 * (listSkus) parses limit as well. Plain unit tests with mocked repositories; InventoryPagingIntegrationTest and
 * V2ListPagingIntegrationTest run a few of these forms through Postgres.
 */
class InventoryServiceReadTest {

    private static final long FIXED_PAGE_PLUS_ONE = InventoryService.MAX_LIMIT + 1L;

    private final StockRepository stock = mock(StockRepository.class);
    private final DetailsRepository details = mock(DetailsRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final InventoryService service = new InventoryService(stock, details, mock(IdempotencyStore.class),
            mock(KeyedResponses.class), transactions);

    /** C3 for v2 (review R-06e): a malformed skuId is answered before any repository or transaction access. */
    @Test
    void v2ReadAndPutWithAMalformedSkuIdTouchNothing() {
        assertThat(service.findSku("bad id")).isEmpty();
        assertThat(service.putDetails("bad id", new SkuDetails("n", "", Optional.empty(), List.of()),
                new DetailsPrecondition.Any())).isInstanceOf(PutResult.InvalidRequest.class);
        verifyNoInteractions(details, transactions);
    }

    // ---- unversioned: list(after), a fixed page of 250 (H4) ----

    private InventoryPage list(String after) {
        when(stock.page(anyString(), anyLong())).thenReturn(List.of());
        return service.list(after);
    }

    /** R4: after is cut at the first NUL and otherwise passed as a plain string; absent means from the start. */
    @ParameterizedTest(name = "after \"{0}\" → \"{1}\"")
    @CsvSource(delimiter = '|', value = {"abc\0x | abc", "\0 | ''", "A-1,B-2 | A-1,B-2", "'' | ''"})
    void listTruncatesAfterAtNul(String after, String expected) {
        list(after);

        verify(stock).page(expected, FIXED_PAGE_PLUS_ONE);
    }

    @Test
    void listWithoutAfterAsksForTheFixedPageFromTheStart() {
        assertThat(list(null).next()).isEmpty();

        verify(stock).page("", FIXED_PAGE_PLUS_ONE);
    }

    /** H4, G9: the extra row only signals the next page; the cursor is the last skuId of the 250. */
    @Test
    void listReturnsNextCursorOnlyWhenAnExtraRowExists() {
        List<InventoryItem> rows = IntStream.rangeClosed(1, 251).mapToObj(i -> new InventoryItem("p%03d".formatted(i), i))
                .toList();
        when(stock.page("", FIXED_PAGE_PLUS_ONE)).thenReturn(rows);
        InventoryPage page = service.list(null);
        assertThat(page.items()).hasSize(250).extracting(InventoryItem::skuId).endsWith("p249", "p250");
        assertThat(page.next().map(InventoryPage.Next::after)).contains("p250");

        when(stock.page("p250", FIXED_PAGE_PLUS_ONE)).thenReturn(rows.subList(250, 251));
        InventoryPage last = service.list("p250");
        assertThat(last.items()).extracting(InventoryItem::skuId).containsExactly("p251");
        assertThat(last.next()).isEmpty();

        when(stock.page("q", FIXED_PAGE_PLUS_ONE)).thenReturn(rows.subList(0, 250));
        assertThat(service.list("q").next()).as("exactly 250 rows: no next page").isEmpty();
    }

    // ---- /v2: listSkus(limit, after) (H5) ----

    private SkuPage listSkus(String limit, String after) {
        when(details.page(anyString(), anyLong())).thenReturn(List.of());
        return service.listSkus(limit, after);
    }

    static Stream<String> unusableLimits() {
        return Stream.of("0", "-1", "-0", "abc", "", " ", "1.5", " 5", "5 ", "1e3", "٣", "2,3");
    }

    /** R4, C2: a missing or unusable limit is ignored and the default page of 250 (plus the extra row) is asked for. */
    @ParameterizedTest(name = "limit \"{0}\"")
    @NullSource
    @MethodSource("unusableLimits")
    void listSkusIgnoresUnusableLimit(String limit) {
        assertThat(listSkus(limit, null).next()).isEmpty();

        verify(details).page("", FIXED_PAGE_PLUS_ONE);
    }

    /** R4, R8: a usable limit is used as is, an explicit plus sign is allowed, and anything above 250 is 250. */
    @ParameterizedTest(name = "limit \"{0}\" → {1}")
    @CsvSource({"1, 1", "+5, 5", "250, 250", "251, 250", "99999999999999999999, 250"})
    void listSkusUsesOrClampsLimit(String limit, int expected) {
        listSkus(limit, null);

        verify(details).page("", expected + 1L);
    }

    /** R4: after is cut at the first NUL on /v2 too. */
    @ParameterizedTest(name = "after \"{0}\" → \"{1}\"")
    @CsvSource(delimiter = '|', value = {"abc\0x | abc", "\0 | ''", "'' | ''"})
    void listSkusTruncatesAfterAtNul(String after, String expected) {
        listSkus(null, after);

        verify(details).page(expected, FIXED_PAGE_PLUS_ONE);
    }

    /** G9: the extra row only signals the next page; the cursor carries the page size used and the last skuId. */
    @Test
    void listSkusReturnsNextCursorOnlyWhenAnExtraRowExists() {
        when(details.page("", 3L)).thenReturn(List.of(sku("a"), sku("b"), sku("c")));
        SkuPage page = service.listSkus("2", null);
        assertThat(page.items()).extracting(SkuItem::skuId).containsExactly("a", "b");
        assertThat(page.next()).isEqualTo(Optional.of(new InventoryPage.Next(2, "b")));

        when(details.page("b", 3L)).thenReturn(List.of(sku("c")));
        SkuPage last = service.listSkus("2", "b");
        assertThat(last.items()).extracting(SkuItem::skuId).containsExactly("c");
        assertThat(last.next()).isEmpty();
    }

    private static SkuItem sku(String skuId) {
        return new SkuItem(skuId, 1, Optional.empty(), 0);
    }
}
