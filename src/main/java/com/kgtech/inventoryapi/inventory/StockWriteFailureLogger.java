package com.kgtech.inventoryapi.inventory;

import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.stereotype.Component;

/** Logs the SKU when a stock write fails for good (W2). */
@Component
class StockWriteFailureLogger {

    void onRetryAborted(MethodRetryEvent event) {
        throw new UnsupportedOperationException("not implemented");
    }
}
