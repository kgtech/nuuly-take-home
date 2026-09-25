package com.kgtech.inventoryapi.inventory;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.stereotype.Component;

/**
 * Logs the SKU when a stock write fails for good (W2): ERROR with the stack trace when serialization retries are
 * exhausted, one WARN line with the root SQLState otherwise. skuId is the first argument of every service write.
 */
@Component
class StockWriteFailureLogger {

    private static final Logger log = LoggerFactory.getLogger(StockWriteFailureLogger.class);

    private final SerializationFailure serializationFailure = new SerializationFailure();

    @EventListener(condition = "#event.retryAborted")
    void onRetryAborted(MethodRetryEvent event) {
        if (event.getMethod().getDeclaringClass() != InventoryService.class) {
            return;
        }
        Object skuId = event.getSource().getArguments()[0];
        if (serializationFailure.shouldRetry(event.getMethod(), event.getFailure())) {
            log.error("Stock write retries exhausted for SKU {}", skuId, event.getFailure());
        } else {
            log.warn("Stock write failed without retry for SKU {} (SQLState {})", skuId, sqlState(event.getFailure()));
        }
    }

    private static String sqlState(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure) instanceof SQLException sql ? sql.getSQLState() : null;
    }
}
