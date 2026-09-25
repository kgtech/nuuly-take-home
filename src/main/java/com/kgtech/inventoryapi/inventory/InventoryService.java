package com.kgtech.inventoryapi.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Stock writes: one SERIALIZABLE transaction per attempt, retried on serialization failure (X1, W2, Y2). */
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

    public StockOutcome.Add add(String skuId, int quantity) {
        throw new UnsupportedOperationException("not implemented");
    }

    public StockOutcome.Purchase purchase(String skuId, int quantity) {
        throw new UnsupportedOperationException("not implemented");
    }
}
