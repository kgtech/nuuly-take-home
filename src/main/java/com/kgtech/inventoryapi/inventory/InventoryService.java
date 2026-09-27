package com.kgtech.inventoryapi.inventory;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.idempotency.Idempotent;
import com.kgtech.inventoryapi.idempotency.Operation;

/**
 * Stock writes: one SERIALIZABLE transaction per attempt, retried on serialization failure (X1, W2, Y2); the
 * Idempotency-Key is handled by the @Idempotent interceptor (Z1). Reads run in a read-only transaction and return
 * balances from the ledger SUM (D3); find checks the skuId before its transaction starts, so an invalid ID borrows no
 * connection (G11, C3).
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

    private final SkuRepository skus;
    private final TransactionTemplate serializable;
    private final TransactionTemplate readOnly;

    InventoryService(SkuRepository skus, PlatformTransactionManager transactionManager) {
        this.skus = skus;
        this.serializable = new TransactionTemplate(transactionManager);
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        serializable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
    }

    @Idempotent(Operation.ADD)
    @Retryable(includes = PessimisticLockingFailureException.class, predicate = SerializationFailure.class,
            maxRetries = 10, delay = 5, jitter = 5, multiplier = 2, maxDelay = 200)
    public WriteResult add(String skuId, int quantity, String idempotencyKey) {
        Optional<WriteResult> rejected = SkuId.rejection(Operation.ADD, skuId);
        if (rejected.isPresent()) {
            return rejected.get(); // S2: no repository or transaction access
        }
        requirePositive(quantity);
        requireNoWeakerTransaction();
        return serializable.execute(status -> skus.add(skuId, quantity)); // new tx, or joins the @Idempotent tx (X1)
    }

    @Idempotent(Operation.PURCHASE)
    @Retryable(includes = PessimisticLockingFailureException.class, predicate = SerializationFailure.class,
            maxRetries = 10, delay = 5, jitter = 5, multiplier = 2, maxDelay = 200)
    public WriteResult purchase(String skuId, int quantity, String idempotencyKey) {
        Optional<WriteResult> rejected = SkuId.rejection(Operation.PURCHASE, skuId);
        if (rejected.isPresent()) {
            return rejected.get(); // S2: no repository or transaction access
        }
        requirePositive(quantity);
        requireNoWeakerTransaction();
        return serializable.execute(status -> skus.purchase(skuId, quantity));
    }

    /** G11, S2, C3: no @Transactional, so a malformed or oversized ID returns before any transaction or query. */
    public Optional<InventoryItem> find(String skuId) {
        if (!SkuId.isValid(skuId)) {
            return Optional.empty();
        }
        Optional<Long> quantity = readOnly.execute(status -> skus.findQuantity(skuId));
        return Objects.requireNonNull(quantity).map(q -> new InventoryItem(skuId, q));
    }

    /** One page of SKUs in sku_id (COLLATE "C") order, at most 250 (G9, R4, C2). */
    @Transactional(readOnly = true)
    public InventoryPage list(String limit, String after) {
        int n = parseLimit(limit);
        String cursor = truncateAtNul(after);
        List<InventoryItem> items = toItems(skus.findQuantitiesAfter(cursor == null ? FIRST : cursor, n + 1L));
        if (items.size() <= n) {
            return new InventoryPage(items, Optional.empty());
        }
        List<InventoryItem> page = items.subList(0, n);
        return new InventoryPage(List.copyOf(page), Optional.of(new InventoryPage.Next(n, page.getLast().skuId())));
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

    private static List<InventoryItem> toItems(List<SkuQuantity> rows) {
        return rows.stream().map(row -> new InventoryItem(row.getSkuId(), row.getQuantity())).toList();
    }

    private static void requirePositive(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
    }

    /** X1: the REQUIRED template would silently join a surrounding transaction at its isolation; refuse that. */
    private static void requireNoWeakerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (isolation == null || isolation != TransactionDefinition.ISOLATION_SERIALIZABLE) {
                throw new IllegalStateException("Stock writes must not join a non-SERIALIZABLE transaction");
            }
        }
    }
}
