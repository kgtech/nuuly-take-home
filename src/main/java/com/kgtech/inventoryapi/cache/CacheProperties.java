package com.kgtech.inventoryapi.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DESIGN-V2 §2–3: the staleness bound for a cached count and the lifetime of a cached replay. */
@ConfigurationProperties("inventory.cache")
public record CacheProperties(Duration stockTtl, Duration replayTtl) {

    public CacheProperties {
        stockTtl = stockTtl == null ? Duration.ofSeconds(5) : stockTtl;
        replayTtl = replayTtl == null ? Duration.ofHours(24) : replayTtl;
        if (stockTtl.isNegative() || stockTtl.isZero() || replayTtl.isNegative() || replayTtl.isZero()) {
            throw new IllegalArgumentException("inventory.cache TTLs must be positive");
        }
    }
}
