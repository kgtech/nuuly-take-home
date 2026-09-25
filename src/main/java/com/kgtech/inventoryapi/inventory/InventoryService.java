package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

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
 * balances from the ledger SUM (D3).
 */
@Service
class InventoryService {

    private final SkuRepository skus;
    private final TransactionTemplate serializable;

    InventoryService(SkuRepository skus, PlatformTransactionManager transactionManager) {
        this.skus = skus;
        this.serializable = new TransactionTemplate(transactionManager);
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        serializable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
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

    @Transactional(readOnly = true)
    public Optional<InventoryItem> find(String skuId) {
        if (!SkuId.isValid(skuId)) {
            return Optional.empty(); // G11: no repository call for a malformed or oversized ID
        }
        return skus.findQuantity(skuId).map(quantity -> new InventoryItem(skuId, quantity));
    }

    /** Every SKU in sku_id (COLLATE "C") order. */
    @Transactional(readOnly = true)
    public List<InventoryItem> findAll() {
        return skus.findAllQuantities().stream()
                .map(row -> new InventoryItem(row.getSkuId(), row.getQuantity()))
                .toList();
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
