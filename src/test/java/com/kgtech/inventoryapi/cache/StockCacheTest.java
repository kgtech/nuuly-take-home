package com.kgtech.inventoryapi.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;

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
    void staleEntryExpiresWithinTheTtl() {
        Tables.seed(jdbc, "hot", 10);
        service.find("hot");
        jdbc.sql("UPDATE sku SET quantity = 99, version = 2 WHERE sku_id = 'hot'").update();

        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(service.find("hot")).contains(new InventoryItem("hot", 99)));
        assertThat(redis.getExpire(StockCache.key("hot"))).isBetween(1L, 1L); // seconds, rounded: a fresh TTL again
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
        Long millis = redis.getExpire(StockCache.key("hot"), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(millis).isBetween(1L, 700L);
    }
}
