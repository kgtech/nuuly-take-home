package com.kgtech.inventoryapi.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.kgtech.inventoryapi.cache.CacheProperties;
import com.kgtech.inventoryapi.cache.RedisGuard;

/**
 * A copy of completed idempotency rows in Redis (DESIGN-V2 §2 step 2): {@code idem:{key}} → hash {op, sku, hash,
 * status, ct, body, created}. The copy never outlives the row's T1 validity: it carries the row's claim time, its TTL
 * is the time left until created + 24h, and a lookup ignores an entry whose age exceeds the replay TTL, so an expired
 * key always falls through to Postgres, which rejects it. A miss or a Redis failure falls through as well.
 */
@Component
class ReplayCache {

    static final String PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> replayPut;
    private final CacheProperties properties;
    private final RedisGuard guard;
    private final Clock clock;

    @Autowired
    ReplayCache(StringRedisTemplate redis, RedisScript<Long> replayPutScript, CacheProperties properties,
            RedisGuard guard) {
        this(redis, replayPutScript, properties, guard, Clock.systemUTC());
    }

    /** For tests that move the clock. */
    ReplayCache(StringRedisTemplate redis, RedisScript<Long> replayPutScript, CacheProperties properties,
            RedisGuard guard, Clock clock) {
        this.redis = redis;
        this.replayPut = replayPutScript;
        this.properties = properties;
        this.guard = guard;
        this.clock = clock;
    }

    /** Replayed when the cached request matches, Rejected when it differs, empty on a miss, expiry or failure. */
    Optional<KeyedResult> lookup(IdempotentRequest request) {
        return guard.call("replay read", () -> {
            Map<String, String> m = redis.<String, String>opsForHash().entries(key(request));
            if (m.isEmpty()) {
                return null;
            }
            Instant created = Instant.ofEpochMilli(Long.parseLong(m.get("created")));
            if (Duration.between(created, clock.instant()).compareTo(properties.replayTtl()) >= 0) {
                return null; // T1: as old as the row's validity, so Postgres decides (and rejects)
            }
            return matches(m, request)
                    ? new KeyedResult.Replayed(new StoredResponse(Integer.parseInt(m.get("status")), m.get("ct"),
                            m.get("body")), created)
                    : new KeyedResult.Rejected();
        });
    }

    /** Called after the Postgres row is committed (or read back complete). Best effort; skipped once expired. */
    void put(IdempotentRequest request, StoredResponse response, Instant createdAt) {
        Duration remaining = properties.replayTtl().minus(Duration.between(createdAt, clock.instant()));
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        guard.run("replay write", () -> redis.execute(replayPut, List.of(key(request)),
                request.operation().dbValue(), request.skuId(), hash(request), Integer.toString(response.status()),
                response.contentType(), response.body(), Long.toString(createdAt.toEpochMilli()),
                Long.toString(remaining.toMillis())));
    }

    private static boolean matches(Map<String, String> m, IdempotentRequest request) {
        return request.operation().dbValue().equals(m.get("op"))
                && request.skuId().equals(m.get("sku"))
                && hash(request).equals(m.get("hash"));
    }

    private static String hash(IdempotentRequest request) {
        return Base64.getEncoder().encodeToString(request.requestHash());
    }

    static String key(IdempotentRequest request) {
        return PREFIX + request.key();
    }
}
