package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * F-08 (#98): {@code PUT /v2/inventory/{skuId}/details} over real HTTP with up to 8 threads on one SKU (W2). Creates
 * race to one 201; If-None-Match: * and If-Match give exactly one winner; a create racing the spec's add and purchase
 * on a new id leaves balance equal to the ledger, and the client never sees a 5xx. Not @Transactional.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuDetailsPutConcurrencyTest {

    private static final int THREADS = 8;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String DETAILS_CHANGED = SkuDetailsApiIntegrationTest.DETAILS_CHANGED;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private HttpClient http;

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void setUp() {
        Tables.reset(jdbc);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    @AfterEach
    void closeClient() {
        http.close();
    }

    private static String newSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Reply send(String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT).header(ACCEPT, "application/json").header(CONTENT_TYPE, "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply putDetails(String sku, String name, String... headers) throws IOException, InterruptedException {
        return send("PUT", "/v2/inventory/" + sku + "/details", "{\"name\":\"" + name + "\"}", headers);
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    @Test
    void concurrentPutCreatesOfOneIdGiveOne201AndTheRest200() throws InterruptedException {
        String sku = newSku("pc");
        AtomicInteger n = new AtomicInteger();

        List<Reply> replies = Concurrently.run(THREADS, () -> putDetails(sku, "Edit " + n.getAndIncrement()));

        assertThat(replies).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(replies).filteredOn(r -> r.status() == 200).hasSize(THREADS - 1);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isZero();
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void concurrentPutCreatesWithIfNoneMatchStarGiveOne201AndTheRest412() throws InterruptedException {
        String sku = newSku("pn");

        List<Reply> replies = Concurrently.run(THREADS, () -> putDetails(sku, "Only", IF_NONE_MATCH, "*"));

        assertThat(replies).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(replies).filteredOn(r -> r.status() == 412).hasSize(THREADS - 1)
                .allSatisfy(r -> assertThat(r.body()).isEqualTo(DETAILS_CHANGED));
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
    }

    @Test
    void concurrentIfMatchPutsToOneVersionApplyExactlyOne() throws Exception {
        String sku = newSku("pm");
        assertThat(putDetails(sku, "Start").status()).isEqualTo(201);
        AtomicInteger n = new AtomicInteger();

        List<Reply> replies = Concurrently.run(THREADS,
                () -> putDetails(sku, "Edit " + n.getAndIncrement(), IF_MATCH, "\"1\""));

        assertThat(replies).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(replies).filteredOn(r -> r.status() == 412).hasSize(THREADS - 1);
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(2);
    }

    /** A PUT-create racing the spec's add and purchase of one new id: every request gets a defined status. */
    @Test
    void aPutCreateRacingAnAddAndAPurchaseKeepsTheLedgerAndTheBalanceEqual() throws InterruptedException {
        String sku = newSku("px");
        AtomicInteger turn = new AtomicInteger();

        List<Reply> replies = Concurrently.run(6, () -> switch (turn.getAndIncrement() % 3) {
            case 0 -> putDetails(sku, "Race");
            case 1 -> send("POST", "/inventory/" + sku, "{\"quantity\":3}");
            default -> send("POST", "/inventory/" + sku + "/purchase", "{\"quantity\":1}");
        });

        assertThat(replies).allSatisfy(r -> assertThat(r.status()).as(r.body()).isLessThan(500));
        assertThat(replies).filteredOn(r -> r.status() == 201).hasSizeLessThanOrEqualTo(1);
        assertThat(replies).allSatisfy(r -> assertThat(r.status()).isIn(200, 201, 400, 404));
        long adds = count("SELECT count(*) FROM inventory_ledger WHERE reason = 'add' AND sku_id = ?", sku);
        long purchases = count("SELECT count(*) FROM inventory_ledger WHERE reason = 'purchase' AND sku_id = ?", sku);
        assertThat(adds).isEqualTo(2);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(adds * 3 - purchases);
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sku WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }
}
