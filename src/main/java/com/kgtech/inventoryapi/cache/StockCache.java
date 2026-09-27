package com.kgtech.inventoryapi.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The most-read stock counts (DESIGN-V2 §3): {@code stock:{skuId}} → hash {q, v} with the stock TTL. Reads populate;
 * writes refresh only an existing entry; a write with an older version never overwrites a newer one.
 */
@Component
public class StockCache {

    static final String PREFIX = "stock:";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> stockSet;
    private final CacheProperties properties;
    private final RedisGuard guard;

    StockCache(StringRedisTemplate redis, RedisScript<Long> stockSetScript, CacheProperties properties,
            RedisGuard guard) {
        this.redis = redis;
        this.stockSet = stockSetScript;
        this.properties = properties;
        this.guard = guard;
    }

    /** The cached quantity, or empty on a miss or when Redis is unavailable. */
    public Optional<Long> get(String skuId) {
        return guard.call("stock read", () -> {
            String q = redis.<String, String>opsForHash().entries(key(skuId)).get("q");
            return q == null ? null : Long.parseLong(q); // a malformed field is a cache failure, not a 500
        });
    }

    /** After a read miss: cache {@code quantity} at {@code version} unless a newer version is already cached. */
    public void populate(String skuId, long quantity, long version) {
        set(skuId, quantity, version, false);
    }

    /** After a committed write: refresh the entry if it exists and the version is newer; never create one. */
    public void refresh(String skuId, long quantity, long version) {
        set(skuId, quantity, version, true);
    }

    /** Whether the last set wrote (for tests): 1 written, 0 skipped, empty when Redis was unavailable. */
    Optional<Long> set(String skuId, long quantity, long version, boolean onlyIfPresent) {
        return guard.call("stock refresh", () -> redis.execute(stockSet, List.of(key(skuId)),
                Long.toString(quantity), Long.toString(version), Long.toString(properties.stockTtl().toMillis()),
                onlyIfPresent ? "1" : "0"));
    }

    /** The raw entry, for tests. */
    Optional<Map<String, String>> entry(String skuId) {
        return guard.call("stock read", () -> redis.<String, String>opsForHash().entries(key(skuId)))
                .filter(m -> !m.isEmpty());
    }

    static String key(String skuId) {
        return PREFIX + skuId;
    }
}
