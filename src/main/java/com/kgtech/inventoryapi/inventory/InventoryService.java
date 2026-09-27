package com.kgtech.inventoryapi.inventory;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.cache.AfterCommit;
import com.kgtech.inventoryapi.cache.StockCache;
import com.kgtech.inventoryapi.idempotency.Idempotent;
import com.kgtech.inventoryapi.idempotency.Operation;

/**
 * Stock writes: one READ COMMITTED transaction each, a conditional row update plus a ledger row (DESIGN-V2 §2); the
 * Idempotency-Key is handled by the @Idempotent interceptor (Z1). After commit the cached count is refreshed.
 * Reads try the cache, then one row lookup (§3).
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
    private final StockCache cache;
    private final TransactionTemplate transaction;

    InventoryService(StockRepository stock, StockCache cache, PlatformTransactionManager transactionManager) {
        this.stock = stock;
        this.cache = cache;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Idempotent(Operation.ADD)
    public WriteResult add(String skuId, int quantity, String idempotencyKey) {
        Optional<WriteResult> rejected = SkuId.rejection(Operation.ADD, skuId);
        if (rejected.isPresent()) {
            return rejected.get(); // S2: no repository or transaction access
        }
        requirePositive(quantity);
        return transaction.execute(status -> stock.add(skuId, quantity)
                .<WriteResult>map(balance -> ok(skuId, balance))
                .orElseGet(StockOutcome.Overflow::new));
    }

    @Idempotent(Operation.PURCHASE)
    public WriteResult purchase(String skuId, int quantity, String idempotencyKey) {
        Optional<WriteResult> rejected = SkuId.rejection(Operation.PURCHASE, skuId);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        requirePositive(quantity);
        return transaction.execute(status -> stock.purchase(skuId, quantity)
                .<WriteResult>map(balance -> ok(skuId, balance))
                .orElseGet(() -> stock.exists(skuId) ? new StockOutcome.Insufficient() : new StockOutcome.NotFound()));
    }

    /** G11 first (no I/O for a malformed id, C-05), then the cache, then one row (DESIGN-V2 §3). */
    public Optional<InventoryItem> find(String skuId) {
        if (!SkuId.isValid(skuId)) {
            return Optional.empty();
        }
        Optional<Long> cached = cache.get(skuId);
        if (cached.isPresent()) {
            return Optional.of(new InventoryItem(skuId, cached.get()));
        }
        return stock.find(skuId).map(balance -> {
            cache.populate(skuId, balance.quantity(), balance.version());
            return new InventoryItem(skuId, balance.quantity());
        });
    }

    /** One page of SKUs in sku_id (COLLATE "C") order, at most 250 (G9, R4, C2). */
    public InventoryPage list(String limit, String after) {
        int n = parseLimit(limit);
        String cursor = truncateAtNul(after);
        List<InventoryItem> items = stock.page(cursor == null ? FIRST : cursor, n + 1L);
        if (items.size() <= n) {
            return new InventoryPage(items, Optional.empty());
        }
        List<InventoryItem> page = items.subList(0, n);
        return new InventoryPage(List.copyOf(page), Optional.of(new InventoryPage.Next(n, page.getLast().skuId())));
    }

    /** The success outcome; the cache refresh runs only after the transaction commits (DESIGN-V2 §2 step 4). */
    private WriteResult ok(String skuId, Balance balance) {
        AfterCommit.run(() -> cache.refresh(skuId, balance.quantity(), balance.version()));
        return new StockOutcome.Ok(balance.quantity());
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

    /** R4: after is never validated. Postgres text cannot hold NUL, so the cursor is cut there. */
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
