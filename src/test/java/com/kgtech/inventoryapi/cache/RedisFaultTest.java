package com.kgtech.inventoryapi.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.TestcontainersConfiguration;
import com.kgtech.inventoryapi.web.HttpConstants;

/**
 * DESIGN-V2 §4 "defined failure" and the exactly-once invariant across Redis faults: with Redis paused (down), the
 * four operations answer exactly as with Redis up; after a flush or a restart a repeated key still replays and never
 * changes stock twice. The shared container is paused/unpaused, so this class must not run in parallel with others.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
class RedisFaultTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RedisConnectionFactory redis;

    @Autowired
    RedisGuard guard;

    private final GenericContainer<?> container = TestcontainersConfiguration.redis();

    @BeforeEach
    void clean() {
        unpause();
        Tables.reset(jdbc);
        Tables.flush(redis);
    }

    @AfterEach
    void restore() {
        unpause();
    }

    private void pause() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private void unpause() {
        var state = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec().getState();
        if (Boolean.TRUE.equals(state.getPaused())) {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
        awaitRedis();
    }

    /** Commands that were in flight while Redis was paused or restarting time out first; wait for a clean PING. */
    private void awaitRedis() {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).ignoreExceptions()
                .untilAsserted(() -> {
                    try (var connection = redis.getConnection()) {
                        assertThat(connection.ping()).isEqualTo("PONG");
                    }
                });
    }

    /** A real container restart: new process, same host port (bound in the test configuration), empty dataset. */
    private void restartRedis() {
        container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
        awaitRedis();
    }

    /** Status, media type without parameters, body: the same reply whether fresh, replayed or Redis-less. */
    private record Reply(int status, String contentType, String body) {
    }

    private Reply send(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse r = mvc.perform(request).andReturn().getResponse();
        String type = r.getContentType() == null ? null : r.getContentType().split(";")[0].trim();
        return new Reply(r.getStatus(), type, r.getContentAsString());
    }

    private Reply add(String sku, int q, String key) throws Exception {
        return send(withKey(post("/inventory/{sku}", sku).contentType(APPLICATION_JSON).accept(APPLICATION_JSON)
                .content("{\"quantity\":" + q + "}"), key));
    }

    private Reply purchase(String sku, int q, String key) throws Exception {
        return send(withKey(post("/inventory/{sku}/purchase", sku).contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON).content("{\"quantity\":" + q + "}"), key));
    }

    private Reply read(String sku) throws Exception {
        return send(get("/inventory/{sku}", sku).accept(APPLICATION_JSON));
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder b, String key) {
        return key == null ? b : b.header(HttpConstants.IDEMPOTENCY_KEY, key);
    }

    private long quantity(String sku) {
        return jdbc.sql("SELECT quantity FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long ledgerRows() {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single();
    }

    @Test
    void everyOperationKeepsWorkingWithRedisDown() throws Exception {
        add("w", 5, null);
        read("w"); // cached
        pause();

        String key = UUID.randomUUID().toString();
        assertThat(add("w", 5, key)).isEqualTo(new Reply(200, "application/json", "{\"skuId\":\"w\",\"quantity\":10}"));
        assertThat(add("w", 5, key)).as("replay through the Postgres claim")
                .isEqualTo(new Reply(200, "application/json", "{\"skuId\":\"w\",\"quantity\":10}"));
        assertThat(purchase("w", 3, null)).isEqualTo(new Reply(200, "application/json", "{\"skuId\":\"w\",\"quantity\":7}"));
        assertThat(purchase("w", 30, null)).isEqualTo(new Reply(400, "text/plain", "Insufficient inventory"));
        assertThat(purchase("ghost", 1, null)).isEqualTo(new Reply(404, "text/plain", "SKU not found"));
        assertThat(read("w")).as("read falls through to Postgres")
                .isEqualTo(new Reply(200, "application/json", "{\"skuId\":\"w\",\"quantity\":7}"));
        assertThat(read("ghost")).isEqualTo(new Reply(404, "text/plain", "SKU not found"));
        assertThat(send(get("/inventory").accept(APPLICATION_JSON)).status()).isEqualTo(200);
        assertThat(send(get("/actuator/health/readiness")).status()).as("A4: still ready without Redis").isEqualTo(200);
        assertThat(send(get("/actuator/health")).status()).as("health reports the Redis component").isEqualTo(503);

        assertThat(quantity("w")).isEqualTo(7);
        assertThat(ledgerRows()).isEqualTo(3);
    }

    @Test
    void repeatedKeySurvivesAFlushAndAPause() throws Exception {
        String key = UUID.randomUUID().toString();
        Reply first = add("w", 5, key);
        assertThat(first.status()).isEqualTo(200);

        Tables.flush(redis);
        assertThat(add("w", 5, key)).isEqualTo(first);

        pause();
        assertThat(add("w", 5, key)).isEqualTo(first);
        assertThat(add("w", 6, key)).as("different body, Redis down: Postgres rejects")
                .isEqualTo(new Reply(400, "text/plain", "Invalid request"));
        unpause();

        assertThat(add("w", 5, key)).isEqualTo(first);
        assertThat(quantity("w")).isEqualTo(5);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    /** Durable: a Redis restart (a new process, empty) changes nothing that was acknowledged. */
    @Test
    void acknowledgedChangesSurviveARedisRestart() throws Exception {
        String key = UUID.randomUUID().toString();
        add("w", 5, null);
        purchase("w", 2, key);
        read("w");

        restartRedis();
        try (var connection = redis.getConnection()) {
            assertThat(connection.keyCommands().exists("stock:w".getBytes())).as("restart emptied Redis").isFalse();
        }

        assertThat(read("w")).isEqualTo(new Reply(200, "application/json", "{\"skuId\":\"w\",\"quantity\":3}"));
        assertThat(purchase("w", 2, key)).isEqualTo(new Reply(200, "application/json", "{\"skuId\":\"w\",\"quantity\":3}"));
        assertThat(quantity("w")).isEqualTo(3);
        assertThat(ledgerRows()).isEqualTo(2);
    }

    /** One WARN per role per 10 s, whatever the request rate (DESIGN-V2 §4). */
    @Test
    void redisFailuresAreLoggedOncePerRoleNotPerRequest(CapturedOutput output) throws Exception {
        add("w", 5, null);
        guard.forgetWarnings(); // an earlier test in this JVM may have warned within the interval
        pause();
        for (int i = 0; i < 5; i++) {
            assertThat(read("w").status()).isEqualTo(200);
        }

        long warnings = output.getOut().lines()
                .filter(line -> line.contains("WARN") && line.contains("Redis unavailable for stock read"))
                .count();
        assertThat(warnings).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain("ERROR");
    }
}
