package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stock writes: one SERIALIZABLE transaction per attempt, retried on serialization failure (X1, W2, Y2).
 * Reads run in a read-only transaction and return balances from the ledger SUM (D3).
 */
@Service
class InventoryService {

    private final SkuRepository skus;
    private final TransactionTemplate serializable;

    InventoryService(SkuRepository skus, PlatformTransactionManager transactionManager) {
        this.skus = skus;
        this.serializable = new TransactionTemplate(transactionManager);
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        serializable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Retryable(includes = PessimisticLockingFailureException.class, predicate = SerializationFailure.class,
            maxRetries = 10, delay = 5, jitter = 5, multiplier = 2, maxDelay = 200)
    public StockOutcome.Add add(String skuId, int quantity) {
        requirePositive(quantity);
        return serializable.execute(status -> skus.add(skuId, quantity));
    }

    @Retryable(includes = PessimisticLockingFailureException.class, predicate = SerializationFailure.class,
            maxRetries = 10, delay = 5, jitter = 5, multiplier = 2, maxDelay = 200)
    public StockOutcome.Purchase purchase(String skuId, int quantity) {
        requirePositive(quantity);
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
}
