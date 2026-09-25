package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC4, R2, W2 over real HTTP: concurrent requests with the same fresh Idempotency-Key produce one stock change and
 * the same response; the losers replay after their 40001 retry. Not @Transactional: tables are emptied before each
 * test (S11). At most 8 threads per SKU (W2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IdempotencyHttpConcurrencyTest {

    private static final int THREADS = 8;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private HttpClient http;

    private record Reply(int quantitySent, int status, String contentType, String body) {
    }

    @BeforeEach
    void setUp() {
        // test-only deletes; the application never deletes key, ledger or sku rows (G5, R9)
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    @AfterEach
    void closeClient() {
        http.close();
    }

    private static String newSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Reply post(String path, int quantity, String key) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":" + quantity + "}"));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(quantity, response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private Reply create(String sku, int quantity, String key) throws IOException, InterruptedException {
        return post("/inventory/" + sku, quantity, key);
    }

    private Reply purchase(String sku, int quantity, String key) throws IOException, InterruptedException {
        return post("/inventory/" + sku + "/purchase", quantity, key);
    }

    private static long itemQuantity(Reply reply, String sku) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.contentType()).startsWith("application/json");
        JsonNode item = JsonMapper.shared().readTree(reply.body());
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("skuId", "quantity");
        assertThat(item.get("skuId").asString()).isEqualTo(sku);
        return item.get("quantity").asLong();
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    private long ledgerSum(String sku) {
        return count("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?", sku);
    }

    private long keyRows() {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single();
    }

    private static void assertAllIdentical(List<Reply> replies) {
        Reply first = replies.getFirst();
        assertThat(replies).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(first.status());
            assertThat(r.contentType()).isEqualTo(first.contentType());
            assertThat(r.body()).isEqualTo(first.body());
        });
    }

    @Test
    void concurrentAddsSameFreshKey() throws Exception {
        String sku = newSku("idem-add");
        String key = UUID.randomUUID().toString();

        List<Reply> replies = Concurrently.run(THREADS, () -> create(sku, 5, key));

        assertThat(replies).extracting(Reply::status).as("never a 500").containsOnly(200);
        assertAllIdentical(replies);
        assertThat(itemQuantity(replies.getFirst(), sku)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(ledgerSum(sku)).isEqualTo(5);
        assertThat(keyRows()).isEqualTo(1);
    }

    @Test
    void concurrentPurchasesSameFreshKey() throws Exception {
        String sku = newSku("idem-buy");
        assertThat(itemQuantity(create(sku, 10, null), sku)).isEqualTo(10);
        String key = UUID.randomUUID().toString();

        List<Reply> replies = Concurrently.run(THREADS, () -> purchase(sku, 3, key));

        assertThat(replies).extracting(Reply::status).as("never a 500").containsOnly(200);
        assertAllIdentical(replies);
        assertThat(itemQuantity(replies.getFirst(), sku)).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(1);
        assertThat(ledgerSum(sku)).isEqualTo(7);
        assertThat(keyRows()).isEqualTo(1);
    }

    /** S8: one body wins the key; every request with the other body gets 400 "Invalid request". */
    @Test
    void concurrentSameKeyDifferentBodies() throws Exception {
        String sku = newSku("idem-mix");
        String key = UUID.randomUUID().toString();
        AtomicInteger next = new AtomicInteger();

        List<Reply> replies = Concurrently.run(THREADS, () -> create(sku, next.getAndIncrement() % 2 + 1, key));

        assertThat(replies).extracting(Reply::status).as("only 200 and 400").allMatch(s -> s == 200 || s == 400);
        Map<Integer, List<Reply>> byQuantity = replies.stream().collect(Collectors.groupingBy(Reply::quantitySent));
        assertThat(byQuantity.keySet()).containsExactlyInAnyOrder(1, 2);
        assertThat(byQuantity.values()).allSatisfy(group -> assertThat(group).hasSize(THREADS / 2));

        int winner = replies.stream().filter(r -> r.status() == 200).findFirst().orElseThrow().quantitySent();
        int loser = winner == 1 ? 2 : 1;
        List<Reply> won = byQuantity.get(winner);
        assertAllIdentical(won);
        assertThat(itemQuantity(won.getFirst(), sku)).isEqualTo(winner);
        assertThat(byQuantity.get(loser)).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.contentType()).startsWith("text/plain");
            assertThat(r.body()).isEqualTo("Invalid request");
        });
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(ledgerSum(sku)).isEqualTo(winner);
        assertThat(keyRows()).isEqualTo(1);
    }
}
