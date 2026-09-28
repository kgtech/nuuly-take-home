package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.transaction.PlatformTransactionManager;

import com.kgtech.inventoryapi.cache.StockCache;

/**
 * R4, R8, C2: InventoryService.list parses limit and cuts after at NUL before the keyset query. Plain unit test with
 * a mocked StockRepository; InventoryPagingIntegrationTest runs a few of these forms through Postgres.
 */
class InventoryServiceReadTest {

    private final StockRepository stock = mock(StockRepository.class);
    private final InventoryService service = new InventoryService(stock, mock(DetailsRepository.class),
            mock(StockCache.class), mock(PlatformTransactionManager.class));

    private InventoryPage list(String limit, String after) {
        when(stock.page(anyString(), anyLong())).thenReturn(List.of());
        return service.list(limit, after);
    }

    static Stream<String> unusableLimits() {
        return Stream.of("0", "-1", "-0", "abc", "", " ", "1.5", " 5", "5 ", "1e3", "٣", "2,3");
    }

    /** R4, C2: a missing or unusable limit is ignored and the default page of 250 (plus the extra row) is asked for. */
    @ParameterizedTest(name = "limit \"{0}\"")
    @NullSource
    @MethodSource("unusableLimits")
    void listIgnoresUnusableLimit(String limit) {
        assertThat(list(limit, null).next()).isEmpty();

        verify(stock).page("", InventoryService.DEFAULT_LIMIT + 1L);
    }

    /** R4, R8: a usable limit is used as is, an explicit plus sign is allowed, and anything above 250 is 250. */
    @ParameterizedTest(name = "limit \"{0}\" → {1}")
    @CsvSource({"1, 1", "+5, 5", "250, 250", "251, 250", "99999999999999999999, 250"})
    void listUsesOrClampsLimit(String limit, int expected) {
        list(limit, null);

        verify(stock).page("", expected + 1L);
    }

    /** R4: after is cut at the first NUL and otherwise passed as a plain string; absent means from the start. */
    @ParameterizedTest(name = "after \"{0}\" → \"{1}\"")
    @CsvSource(delimiter = '|', value = {"abc\0x | abc", "\0 | ''", "A-1,B-2 | A-1,B-2", "'' | ''"})
    void listTruncatesAfterAtNul(String after, String expected) {
        list(null, after);

        verify(stock).page(expected, InventoryService.DEFAULT_LIMIT + 1L);
    }

    @Test
    void listWithoutAfterStartsBeforeEverySku() {
        list("2", null);

        verify(stock).page("", 3L);
    }

    /** G9: the extra row only signals the next page; the Link carries the page size used and the last skuId. */
    @Test
    void listReturnsNextCursorOnlyWhenAnExtraRowExists() {
        when(stock.page("", 3L)).thenReturn(List.of(new InventoryItem("a", 1), new InventoryItem("b", 2),
                new InventoryItem("c", 3)));
        InventoryPage page = service.list("2", null);
        assertThat(page.items()).extracting(InventoryItem::skuId).containsExactly("a", "b");
        assertThat(page.next()).isEqualTo(Optional.of(new InventoryPage.Next(2, "b")));

        when(stock.page("b", 3L)).thenReturn(List.of(new InventoryItem("c", 3)));
        InventoryPage last = service.list("2", "b");
        assertThat(last.items()).extracting(InventoryItem::skuId).containsExactly("c");
        assertThat(last.next()).isEmpty();
    }
}
