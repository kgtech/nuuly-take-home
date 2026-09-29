package com.kgtech.inventoryapi.inventory;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.idempotency.IdempotencyKey;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore;
import com.kgtech.inventoryapi.idempotency.IdempotencyStore.Keyed;
import com.kgtech.inventoryapi.idempotency.Operation;

/**
 * Stock writes: one READ COMMITTED transaction each, a conditional update of the balance row plus a ledger row (E1);
 * with an Idempotency-Key the write runs through IdempotencyStore (A33). Reads are single autocommit queries with no
 * transaction, and find checks the skuId first, so an invalid ID borrows no connection (G11, C3, E1).
 */
@Service
public class InventoryService {

    /** R8: the largest page. */
    public static final int MAX_LIMIT = 250;
    private static final BigInteger MAX_LIMIT_BIG = BigInteger.valueOf(MAX_LIMIT);
    /** R4: ASCII digits with an optional sign; anything else is ignored. */
    private static final Pattern LIMIT = Pattern.compile("[+-]?[0-9]+");
    /** G9, C2: an absent or ignored limit means the largest page. */
    public static final int DEFAULT_LIMIT = MAX_LIMIT;
    /** Every sku_id is non-empty, so the empty cursor starts before all of them. */
    private static final String FIRST = "";

    private final StockRepository stock;
    private final IdempotencyStore idempotency;
    private final KeyedResponses responses;
    private final TransactionTemplate transaction;

    InventoryService(StockRepository stock, IdempotencyStore idempotency, KeyedResponses responses,
            PlatformTransactionManager transactionManager) {
        this.stock = stock;
        this.idempotency = idempotency;
        this.responses = responses;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** Ok or Overflow (U1, A38). A malformed skuId is 400 "Invalid request" (G11). */
    public WriteResult<StockOutcome.Add> add(String skuId, int quantity, String idempotencyKey) {
        requirePositive(quantity);
        return write(Operation.ADD, skuId, quantity, idempotencyKey, new WriteResult.InvalidRequest<>(),
                () -> stock.add(skuId, quantity)
                        .<StockOutcome.Add>map(balance -> new StockOutcome.Ok(balance.quantity()))
                        .orElseGet(StockOutcome.Overflow::new));
    }

    /**
     * Ok, NotFound or Insufficient (A38). A malformed skuId is 404 "SKU not found": GET and purchase have no 400 for it
     * in the spec (G11).
     */
    public WriteResult<StockOutcome.Purchase> purchase(String skuId, int quantity, String idempotencyKey) {
        requirePositive(quantity);
        return write(Operation.PURCHASE, skuId, quantity, idempotencyKey,
                new WriteResult.Done<>(new StockOutcome.NotFound()), () -> stock.purchase(skuId, quantity));
    }

    /**
     * U3, A34, after the controller's body validation: the Idempotency-Key format (S3), then the skuId (G11, S2), each
     * once, before any I/O and never stored. Without a key (G8) the write runs in the service's own READ COMMITTED
     * transaction. With one, IdempotencyStore.run claims the key, runs the write, renders its outcome through
     * KeyedResponses and stores it in one transaction, or replays or rejects (A33). The canonical request is the
     * quantity's decimal digits (Y3).
     */
    private <O extends StockOutcome> WriteResult<O> write(Operation operation, String skuId, int quantity,
            String idempotencyKey, WriteResult<O> malformedSkuId, Supplier<O> action) {
        if (idempotencyKey == null) {
            return SkuId.isValid(skuId) ? new WriteResult.Done<>(transaction.execute(status -> action.get()))
                    : malformedSkuId;
        }
        Optional<UUID> key = IdempotencyKey.parse(idempotencyKey);
        if (key.isEmpty()) {
            return new WriteResult.InvalidRequest<>();
        }
        if (!SkuId.isValid(skuId)) {
            return malformedSkuId;
        }
        return switch (idempotency.run(key.get(), operation, skuId, Integer.toString(quantity),
                () -> responses.toStored(skuId, action.get()))) {
            case Keyed.Response response -> new WriteResult.Stored<>(response.response());
            case Keyed.Invalid _ -> new WriteResult.InvalidRequest<>();
        };
    }

    /** G11, S2, C3, E1: the skuId first, then one autocommit row lookup, which never waits on a writer. */
    public Optional<InventoryItem> find(String skuId) {
        if (!SkuId.isValid(skuId)) {
            return Optional.empty();
        }
        return stock.find(skuId).map(balance -> new InventoryItem(skuId, balance.quantity()));
    }

    /**
     * One page of SKUs in sku_id (COLLATE "C") order, at most 250 (G9, R4, C2), from one autocommit keyset query: it
     * asks for one row more than the page, and that row's presence means a next page.
     */
    public Page<InventoryItem> list(String limit, String after) {
        int n = parseLimit(limit);
        String cursor = truncateAtNul(after);
        List<InventoryItem> items = stock.page(cursor == null ? FIRST : cursor, n + 1L);
        if (items.size() <= n) {
            return new Page<>(items, Optional.empty());
        }
        List<InventoryItem> page = items.subList(0, n);
        return new Page<>(List.copyOf(page), Optional.of(new Page.Next(n, page.getLast().skuId())));
    }

    /** R4, R8, C2: a positive ASCII integer, clamped to 250; blank, non-numeric, zero or negative → 250. */
    private static int parseLimit(String raw) {
        if (raw == null || !LIMIT.matcher(raw).matches()) {
            return DEFAULT_LIMIT;
        }
        BigInteger value = new BigInteger(raw);
        if (value.signum() <= 0) {
            return DEFAULT_LIMIT;
        }
        return value.min(MAX_LIMIT_BIG).intValueExact();
    }

    /**
     * R4: after is never validated. Postgres text cannot hold NUL, and every sku_id sorts above the part before the
     * first NUL, so the cursor is cut there.
     */
    private static String truncateAtNul(String after) {
        if (after == null) {
            return null;
        }
        int nul = after.indexOf('\0');
        return nul < 0 ? after : after.substring(0, nul);
    }

    private static void requirePositive(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
    }
}
