package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;
import com.zaxxer.hikari.HikariDataSource;

/**
 * M-14 over real HTTP: a stalled row lock or a starved pool must not hang a request for Hikari's default 30 s. A
 * Postgres lock_timeout of 5 s makes a write that waits on a locked row fail with 500 "Internal server error" (a
 * server fault, which G6 allows), and a Hikari connection-timeout of 3000 ms answers a request that finds the pool
 * empty. The lock is held by a separate connection outside the application's pool. Not @Transactional.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LockTimeoutIntegrationTest {

    /** The answer must come well inside Hikari's 30 s default; the configured timeouts are 5 s and 3 s. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    private HttpClient http;
    private Connection holder;

    @BeforeEach
    void setUp() throws SQLException {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "LOCKED", 5);
        Tables.seed(jdbc, "FREE", 5);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void release() throws SQLException {
        http.close();
        if (holder != null && !holder.isClosed()) {
            holder.rollback();
            holder.close();
        }
    }

    /** Locks the row from a connection of its own (no pool, so the application's session settings don't apply). */
    private void holdRowLock(String skuId) throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        holder = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        holder.setAutoCommit(false);
        try (var statement = holder.createStatement()) {
            statement.executeUpdate("UPDATE sku SET quantity = quantity WHERE sku_id = '" + skuId + "'");
        }
    }

    private HttpRequest purchase(String skuId, Duration timeout) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/inventory/" + skuId + "/purchase"))
                .timeout(timeout)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}")).build();
    }

    private long quantity(String skuId) {
        return jdbc.sql("SELECT quantity FROM sku WHERE sku_id = ?").param(skuId).query(Long.class).single();
    }

    /**
     * The lock is held for about 7 s, longer than the 5 s lock_timeout: the purchase gives up at about 5 s with 500,
     * not after the lock is released (which would answer 200) or after 30 s. The row is unchanged, and once the lock
     * is gone the same SKU and another one work.
     */
    @Test
    void aPurchaseWaitingOnALockedRowAnswers500AtTheLockTimeoutAndTheSkuRecovers() throws Exception {
        holdRowLock("LOCKED");
        Thread releaser = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(7_000);
                holder.commit();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long started = System.nanoTime();

        HttpResponse<String> response = http.send(purchase("LOCKED", Duration.ofSeconds(20)),
                HttpResponse.BodyHandlers.ofString());

        Duration waited = Duration.ofNanos(System.nanoTime() - started);
        assertThat(response.statusCode()).as("after %s: %s", waited, response.body()).isEqualTo(500);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("")).startsWith("text/plain");
        assertThat(response.body()).isEqualTo("Internal server error");
        assertThat(waited).as("a lock timeout, not a hang").isLessThan(BOUND);
        releaser.join(30_000);
        assertThat(quantity("LOCKED")).as("the failed purchase changed nothing").isEqualTo(5);
        assertThat(http.send(purchase("FREE", BOUND), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(http.send(purchase("LOCKED", BOUND), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
        assertThat(quantity("LOCKED")).isEqualTo(4);
    }

    /**
     * 12 purchases pile up on the locked row and take the pool's connections; a request for an unrelated SKU still
     * gets an answer (200 once a connection frees, or 500 when the pool wait times out) inside the bound, while the
     * lock is still held. Without the timeouts it would wait for the lock to be released or for Hikari's 30 s.
     */
    @Test
    void anUnrelatedRequestIsAnsweredWhileTwelvePurchasesWaitOnALockedRow() throws Exception {
        holdRowLock("LOCKED");
        List<CompletableFuture<HttpResponse<String>>> waiting = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            waiting.add(http.sendAsync(purchase("LOCKED", Duration.ofSeconds(60)), HttpResponse.BodyHandlers.ofString()));
        }
        Thread.sleep(500);
        long started = System.nanoTime();
        try {
            HttpResponse<String> unrelated = http.send(purchase("FREE", BOUND), HttpResponse.BodyHandlers.ofString());

            assertThat(unrelated.statusCode()).as(unrelated.body()).isIn(200, 500);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BOUND);
        } finally {
            holder.rollback();
            CompletableFuture.allOf(waiting.toArray(CompletableFuture[]::new)).get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(waiting).allSatisfy(response -> assertThat(response.join().statusCode()).isIn(200, 400, 500));
        assertThat(quantity("LOCKED")).as("only purchases that completed changed it").isBetween(0L, 5L);
    }
}
