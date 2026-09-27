package com.kgtech.inventoryapi.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DESIGN-V2 §3: the staleness bound for a cached count. The replay copy's lifetime is not configurable: it is
 * {@code IdempotencyStore.KEY_VALIDITY}, so Redis can never outlive the Postgres row's validity.
 */
@ConfigurationProperties("inventory.cache")
public record CacheProperties(Duration stockTtl) {

    public CacheProperties {
        stockTtl = stockTtl == null ? Duration.ofSeconds(5) : stockTtl;
        if (stockTtl.isNegative() || stockTtl.isZero()) {
            throw new IllegalArgumentException("inventory.cache.stock-ttl must be positive");
        }
    }
}
