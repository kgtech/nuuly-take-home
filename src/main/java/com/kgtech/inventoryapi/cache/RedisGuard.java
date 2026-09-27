package com.kgtech.inventoryapi.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a Redis call so that any failure (down, slow, refused, script error) becomes an empty result instead of an
 * exception (DESIGN-V2 §4). One WARN line per role per 10 s at most; the rest are DEBUG.
 */
@Component
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);
    static final Duration WARN_INTERVAL = Duration.ofSeconds(10);

    private final ConcurrentHashMap<String, AtomicLong> lastWarnNanos = new ConcurrentHashMap<>();

    /** Forgets the rate-limit state (tests only). */
    void forgetWarnings() {
        lastWarnNanos.clear();
    }

    /** {@code role} names the caller in the log, e.g. "stock read". */
    public <T> Optional<T> call(String role, Supplier<T> redisCall) {
        try {
            return Optional.ofNullable(redisCall.get());
        } catch (RuntimeException e) {
            warn(role, e);
            return Optional.empty();
        }
    }

    /** Same as {@link #call} for a call with no result. Returns whether it succeeded. */
    public boolean run(String role, Runnable redisCall) {
        return call(role, () -> {
            redisCall.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    private void warn(String role, RuntimeException e) {
        long now = System.nanoTime();
        AtomicLong last = lastWarnNanos.computeIfAbsent(role, r -> new AtomicLong(now - WARN_INTERVAL.toNanos() - 1));
        long previous = last.get();
        if (now - previous >= WARN_INTERVAL.toNanos() && last.compareAndSet(previous, now)) {
            log.warn("Redis unavailable for {}; continuing with Postgres ({}: {})", role,
                    e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.debug("Redis unavailable for {} (suppressed)", role, e);
        }
    }
}
