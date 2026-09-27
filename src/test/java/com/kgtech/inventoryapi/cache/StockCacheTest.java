package com.kgtech.inventoryapi.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.inventory.InventoryItem;
import com.kgtech.inventoryapi.inventory.InventoryService;

/**
 * DESIGN-V2 §3: reads populate, writes refresh only existing entries, an older version never overwrites a newer one,
 * and a stale entry lives at most the stock TTL. The TTL is shortened here so the bound can be observed.
 */
@IntegrationTest(properties = "inventory.cache.stock-ttl=700ms")
class StockCacheTest {

    @Autowired
    StockCache cache;

    @Autowired
    InventoryService service;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisConnectionFactory connections;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    @Test
    void readMissPopulatesAndReadHitSkipsPostgres() {
        Tables.seed(jdbc, "hot", 10);

        assertThat(cache.entry("hot")).isEmpty();
        assertThat(service.find("hot")).contains(new InventoryItem("hot", 10));
        assertThat(cache.entry("hot")).contains(Map.of("q", "10", "v", "1"));

        // A change written behind the cache's back (no refresh hook): the hit is served from Redis.
        jdbc.sql("UPDATE sku SET quantity = 99, version = 2 WHERE sku_id = 'hot'").update();
        assertThat(service.find("hot")).contains(new InventoryItem("hot", 10));
    }

    /** The staleness bound: after the TTL the next read is correct again. */
    @Test
    void staleEntryExpiresWithinTheTtl() throws Exception {
        Tables.seed(jdbc, "hot", 10);
        service.find("hot");
        jdbc.sql("UPDATE sku SET quantity = 99, version = 2 WHERE sku_id = 'hot'").update();

        // Still stale halfway through the TTL (the bound is real, not "eventually").
        Thread.sleep(300);
        assertThat(service.find("hot")).contains(new InventoryItem("hot", 10));
        // Correct once the TTL (700 ms) has passed, with a small margin.
        Thread.sleep(600);
        assertThat(service.find("hot")).contains(new InventoryItem("hot", 99));
        Long ttl = redis.getExpire(StockCache.key("hot"), TimeUnit.MILLISECONDS);
        assertThat(ttl).as("a fresh TTL after the re-populate").isBetween(400L, 700L);
    }

    /** DESIGN-V2 §3: a refresh keeps the remaining TTL; only a populate sets it. */
    @Test
    void refreshKeepsTheRemainingTtl() throws Exception {
        cache.populate("hot", 1, 1);
        Thread.sleep(300);
        assertThat(cache.set("hot", 2, 2, true)).contains(1L);
        Long ttl = redis.getExpire(StockCache.key("hot"), TimeUnit.MILLISECONDS);
        assertThat(ttl).as("remaining TTL after the refresh").isBetween(1L, 420L);
        assertThat(cache.entry("hot")).contains(Map.of("q", "2", "v", "2"));
    }

    @Test
    void committedWriteRefreshesAnExistingEntry() {
        service.add("hot", 10, null);
        assertThat(cache.entry("hot")).as("writes never populate").isEmpty();

        service.find("hot");
        service.purchase("hot", 4, null);

        assertThat(cache.entry("hot")).contains(Map.of("q", "6", "v", "2"));
        assertThat(service.find("hot")).contains(new InventoryItem("hot", 6));
    }

    @Test
    void olderVersionNeverOverwritesNewer() {
        cache.populate("hot", 6, 2);
        assertThat(cache.set("hot", 10, 1, false)).contains(0L);
        assertThat(cache.set("hot", 6, 2, false)).contains(0L);
        assertThat(cache.entry("hot")).contains(Map.of("q", "6", "v", "2"));

        assertThat(cache.set("hot", 3, 3, true)).contains(1L);
        assertThat(cache.entry("hot")).contains(Map.of("q", "3", "v", "3"));
        assertThat(cache.set("cold", 1, 1, true)).as("refresh never creates an entry").contains(0L);
        assertThat(cache.entry("cold")).isEmpty();
    }

    @Test
    void entriesCarryTheConfiguredTtl() {
        cache.populate("hot", 1, 1);
        Long millis = redis.getExpire(StockCache.key("hot"), TimeUnit.MILLISECONDS);
        assertThat(millis).isBetween(1L, 700L);
    }
}
