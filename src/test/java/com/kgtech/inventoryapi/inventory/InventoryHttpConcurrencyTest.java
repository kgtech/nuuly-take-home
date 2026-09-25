package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D9, S11, W2 over real HTTP (#4): concurrent purchases and adds through Tomcat, the filter chain and the Hikari
 * pool, asserting the client only ever sees the spec's 200 and 400 (never a 500). Not @Transactional: every request
 * commits its own SERIALIZABLE transaction, so the tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class InventoryHttpConcurrencyTest {

    /** W2 caps concurrency tests at 8 threads per SKU. */
    private static final int THREADS = 8;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private HttpClient http;

    private record Reply(int status, String contentType, String body) {
    }

    @BeforeEach
    void setUp() {
        // test-only deletes; the application never deletes ledger or sku rows (G5)
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

    private Reply send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request.timeout(TIMEOUT).header(ACCEPT, "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue(CONTENT_TYPE).orElse(""),
                response.body());
    }

    private Reply post(String path, long quantity) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":" + quantity + "}")));
    }

    private Reply create(String sku, long quantity) throws IOException, InterruptedException {
        return post("/inventory/" + sku, quantity);
    }

    private Reply purchase(String sku, long quantity) throws IOException, InterruptedException {
        return post("/inventory/" + sku + "/purchase", quantity);
    }

    private Reply find(String sku) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/inventory/" + sku)).GET());
    }

    /** Asserts a 200 JSON item for {@code sku} and returns its quantity. */
    private static long itemQuantity(Reply reply, String sku) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.contentType()).startsWith("application/json");
        JsonNode item = JsonMapper.shared().readTree(reply.body());
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("skuId", "quantity");
        assertThat(item.get("skuId").asString()).isEqualTo(sku);
        return item.get("quantity").asLong();
    }

    private long ledgerSum(String sku) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?")
                .param(sku).query(Long.class).single();
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7})
    void concurrentPurchasesNeverOversell(int stock) throws Exception {
        String sku = newSku("race-buy");
        assertThat(itemQuantity(create(sku, stock), sku)).isEqualTo(stock);

        List<Reply> replies = Concurrently.run(THREADS, () -> purchase(sku, 1));

        assertThat(replies).extracting(Reply::status).as("only the spec's 200 and 400").allMatch(
                s -> s == 200 || s == 400);
        List<Reply> sold = replies.stream().filter(r -> r.status() == 200).toList();
        List<Reply> refused = replies.stream().filter(r -> r.status() == 400).toList();
        assertThat(sold).extracting(r -> itemQuantity(r, sku))
                .containsExactlyInAnyOrderElementsOf(LongStream.range(0, stock).boxed().toList());
        assertThat(refused).hasSize(THREADS - stock).allSatisfy(r -> {
            assertThat(r.contentType()).startsWith("text/plain");
            assertThat(r.body()).isEqualTo("Insufficient inventory");
        });
        assertThat(itemQuantity(find(sku), sku)).isZero();
        assertThat(ledgerSum(sku)).isZero();
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(stock);
    }

    @Test
    void concurrentAddsAreNeverLost() throws Exception {
        String sku = newSku("race-add");

        List<Reply> replies = Concurrently.run(THREADS, () -> create(sku, 1));

        assertThat(replies).extracting(r -> itemQuantity(r, sku))
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, THREADS).boxed().toList());
        assertThat(itemQuantity(find(sku), sku)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(THREADS);
        assertThat(ledgerSum(sku)).isEqualTo(THREADS);
    }
}
