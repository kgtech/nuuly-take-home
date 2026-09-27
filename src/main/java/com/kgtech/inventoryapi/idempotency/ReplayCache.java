package com.kgtech.inventoryapi.idempotency;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.kgtech.inventoryapi.cache.CacheProperties;
import com.kgtech.inventoryapi.cache.RedisGuard;

/**
 * A copy of completed idempotency rows in Redis (DESIGN-V2 §2 step 2): {@code idem:{key}} → hash {op, sku, hash,
 * status, ct, body} with the replay TTL. A hit answers a repeat without Postgres; a miss or a Redis failure falls
 * through to the Postgres claim, which stays the source of truth.
 */
@Component
class ReplayCache {

    static final String PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final CacheProperties properties;
    private final RedisGuard guard;

    ReplayCache(StringRedisTemplate redis, CacheProperties properties, RedisGuard guard) {
        this.redis = redis;
        this.properties = properties;
        this.guard = guard;
    }

    /** Replayed when the cached request matches, Rejected when it differs, empty on a miss or failure. */
    Optional<KeyedResult> lookup(IdempotentRequest request) {
        return guard.call("replay read", () -> redis.<String, String>opsForHash().entries(key(request)))
                .filter(m -> !m.isEmpty())
                .map(m -> matches(m, request)
                        ? new KeyedResult.Replayed(new StoredResponse(Integer.parseInt(m.get("status")), m.get("ct"),
                                m.get("body")))
                        : new KeyedResult.Rejected());
    }

    /** Called after the Postgres row is committed (or read back complete). Best effort. */
    void put(IdempotentRequest request, StoredResponse response) {
        String key = key(request);
        guard.run("replay write", () -> {
            redis.opsForHash().putAll(key, Map.of(
                    "op", request.operation().dbValue(),
                    "sku", request.skuId(),
                    "hash", Base64.getEncoder().encodeToString(request.requestHash()),
                    "status", Integer.toString(response.status()),
                    "ct", response.contentType(),
                    "body", response.body()));
            redis.expire(key, properties.replayTtl());
        });
    }

    private static boolean matches(Map<String, String> m, IdempotentRequest request) {
        return request.operation().dbValue().equals(m.get("op"))
                && request.skuId().equals(m.get("sku"))
                && Base64.getEncoder().encodeToString(request.requestHash()).equals(m.get("hash"));
    }

    static String key(IdempotentRequest request) {
        return PREFIX + request.key();
    }
}
