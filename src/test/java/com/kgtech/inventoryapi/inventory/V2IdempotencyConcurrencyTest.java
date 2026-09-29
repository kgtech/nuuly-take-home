package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.inventory.V2Writes.INSUFFICIENT_INVENTORY;
import static com.kgtech.inventoryapi.inventory.V2Writes.INVALID_REQUEST;
import static com.kgtech.inventoryapi.inventory.V2Writes.TIMEOUT;
import static com.kgtech.inventoryapi.inventory.V2Writes.itemQuantity;
import static com.kgtech.inventoryapi.inventory.V2Writes.newKey;
import static com.kgtech.inventoryapi.inventory.V2Writes.quantityJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.inventory.V2Writes.Op;
import com.kgtech.inventoryapi.inventory.V2Writes.Reply;

/**
 * Invariants 1 and 2, H2, R2, W2 over real HTTP with at most 8 threads per SKU: concurrent /v2 requests with one fresh
 * key change stock once and get the same response; one key with different quantities has one winner; distinct keys
 * never oversell, alone or mixed with unversioned purchases. A start latch releases the threads together; whether they
 * overlapped is not asserted, the invariants hold either way. Not @Transactional: tables are emptied before each test.
 */
@IntegrationTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V2IdempotencyConcurrencyTest {

    private static final int THREADS = 8;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private HttpClient http;

    private record Sent(long quantity, Reply reply) {
    }

    @BeforeEach
    void setUp() {
        Tables.reset(jdbc);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    @AfterEach
    void closeClientAndCheckTheBalances() {
        http.close();
        assertThat(Invariants.balanceMismatches(jdbc)).as("sku.quantity equals the ledger SUM").isEmpty();
        assertThat(Invariants.minQuantity(jdbc)).as("no oversell").isGreaterThanOrEqualTo(0);
    }

    private static String newSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Reply send(Op op, String sku, long quantity, String key) throws IOException, InterruptedException {
        return V2Writes.post(http, port, op.path(sku), quantityJson(quantity), key);
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    private long keyRows() {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single();
    }

    private static void assertAllIdentical(List<Reply> replies) {
        assertThat(replies).allSatisfy(reply -> assertThat(reply).isEqualTo(replies.getFirst()));
    }

    @Test
    void concurrentAddsWithOneFreshKeyChangeStockOnce() throws Exception {
        String sku = newSku("v2-add");
        String key = newKey();

        List<Reply> replies = Concurrently.run(THREADS, () -> send(Op.ADD, sku, 5, key));

        assertThat(replies).extracting(Reply::status).as("never a 500").containsOnly(200);
        assertAllIdentical(replies);
        assertThat(itemQuantity(replies.getFirst(), sku)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(5);
        assertThat(keyRows()).isEqualTo(1);
    }

    @Test
    void concurrentPurchasesWithOneFreshKeyChangeStockOnce() throws Exception {
        String sku = newSku("v2-buy");
        Tables.seed(jdbc, sku, 10);
        String key = newKey();

        List<Reply> replies = Concurrently.run(THREADS, () -> send(Op.PURCHASE, sku, 3, key));

        assertThat(replies).extracting(Reply::status).as("never a 500").containsOnly(200);
        assertAllIdentical(replies);
        assertThat(itemQuantity(replies.getFirst(), sku)).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(1);
        assertThat(keyRows()).isEqualTo(1);
    }

    /** S8: one quantity wins the key; every request with the other quantity gets 400 "Invalid request". */
    @Test
    void oneKeyWithDifferentQuantitiesHasOneWinner() throws Exception {
        String sku = newSku("v2-mix");
        String key = newKey();
        AtomicInteger next = new AtomicInteger();

        List<Sent> sent = Concurrently.run(THREADS, () -> {
            long quantity = next.getAndIncrement() % 2 + 1;
            return new Sent(quantity, send(Op.ADD, sku, quantity, key));
        });

        assertThat(sent).extracting(s -> s.reply().status()).as("only 200 and 400")
                .allMatch(status -> status == 200 || status == 400);
        Map<Long, List<Reply>> byQuantity = sent.stream()
                .collect(Collectors.groupingBy(Sent::quantity, Collectors.mapping(Sent::reply, Collectors.toList())));
        assertThat(byQuantity.keySet()).containsExactlyInAnyOrder(1L, 2L);
        long winner = sent.stream().filter(s -> s.reply().status() == 200).findFirst().orElseThrow().quantity();
        long loser = winner == 1 ? 2 : 1;
        assertAllIdentical(byQuantity.get(winner));
        assertThat(byQuantity.get(winner)).extracting(Reply::status).containsOnly(200);
        assertThat(itemQuantity(byQuantity.get(winner).getFirst(), sku)).isEqualTo(winner);
        assertThat(byQuantity.get(loser)).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.contentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
            assertThat(reply.body()).isEqualTo(INVALID_REQUEST);
        });
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(winner);
        assertThat(keyRows()).isEqualTo(1);
    }

    /** Invariant 1: with stock M < N and a distinct key each, exactly M purchases succeed and stock ends at 0. */
    @Test
    void distinctKeysNeverOversell() throws Exception {
        String sku = newSku("v2-many");
        Tables.seed(jdbc, sku, 3);

        List<Reply> replies = Concurrently.run(THREADS, () -> send(Op.PURCHASE, sku, 1, newKey()));

        Map<Integer, List<Reply>> byStatus = replies.stream().collect(Collectors.groupingBy(Reply::status));
        assertThat(byStatus.keySet()).as("only 200 and 400, never a 500").containsExactlyInAnyOrder(200, 400);
        assertThat(byStatus.get(200)).hasSize(3);
        assertThat(byStatus.get(200).stream().map(reply -> itemQuantity(reply, sku)).toList())
                .containsExactlyInAnyOrder(2L, 1L, 0L);
        assertThat(byStatus.get(400)).hasSize(THREADS - 3).allSatisfy(reply -> {
            assertThat(reply.contentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
            assertThat(reply.body()).isEqualTo(INSUFFICIENT_INVENTORY);
        });
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isZero();
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(3);
        assertThat(keyRows()).as("every business outcome is stored").isEqualTo(THREADS);
    }

    /** R2, W2: distinct fresh keys each add once; every add sees a distinct running total. */
    @Test
    void distinctKeysEachAddOnce() throws Exception {
        String sku = newSku("v2-adds");

        List<Reply> replies = Concurrently.run(THREADS, () -> send(Op.ADD, sku, 1, newKey()));

        assertThat(replies).extracting(Reply::status).as("never a 500").containsOnly(200);
        assertThat(replies.stream().map(reply -> itemQuantity(reply, sku)).toList())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(THREADS);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(THREADS);
        assertThat(keyRows()).isEqualTo(THREADS);
    }

    /**
     * Invariant 1 across versions: half the threads purchase through the unversioned route (no key), half through
     * /v2 (a distinct key each), for stock M < N; in total exactly M succeed and stock ends at 0.
     */
    @Test
    void mixedVersionsNeverOversell() throws Exception {
        String sku = newSku("mixed");
        Tables.seed(jdbc, sku, 4);
        AtomicInteger turn = new AtomicInteger();

        List<Reply> replies = Concurrently.run(THREADS, () -> turn.getAndIncrement() % 2 == 0
                ? V2Writes.post(http, port, "/inventory/" + sku + "/purchase", quantityJson(1), null)
                : send(Op.PURCHASE, sku, 1, newKey()));

        assertThat(replies).extracting(Reply::status).as("only 200 and 400, never a 500")
                .allMatch(status -> status == 200 || status == 400);
        assertThat(replies.stream().filter(reply -> reply.status() == 200)).as("successes in total").hasSize(4);
        assertThat(replies.stream().filter(reply -> reply.status() == 400)).hasSize(THREADS - 4)
                .allSatisfy(reply -> assertThat(reply.body()).isEqualTo(INSUFFICIENT_INVENTORY));
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isZero();
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(4);
        assertThat(keyRows()).as("only the /v2 requests store a key").isEqualTo(THREADS / 2);
    }

    /**
     * Invariants 1 and 3 with adds in the mix: unversioned and /v2 adds and purchases race on one SKU. Every add
     * succeeds; the final balance is the seed plus the adds minus the purchases that got 200, equals the ledger SUM
     * (the after-each check too), and never dips below zero.
     */
    @Test
    void mixedVersionAddsAndPurchasesKeepTheBalanceEqualToTheLedger() throws Exception {
        String sku = newSku("mixed-rw");
        Tables.seed(jdbc, sku, 2);
        AtomicInteger turn = new AtomicInteger();

        List<Sent> sent = Concurrently.run(THREADS, () -> {
            int n = turn.getAndIncrement() % 4;
            Op op = n < 2 ? Op.ADD : Op.PURCHASE;
            boolean v2 = n % 2 == 1;
            Reply reply = v2 ? send(op, sku, 1, newKey())
                    : V2Writes.post(http, port, "/inventory/" + sku + (op == Op.ADD ? "" : "/purchase"),
                            quantityJson(1), null);
            return new Sent(op == Op.ADD ? 1 : -1, reply);
        });

        assertThat(sent).extracting(s -> s.reply().status()).as("only 200 and 400, never a 500")
                .allMatch(status -> status == 200 || status == 400);
        List<Sent> adds = sent.stream().filter(s -> s.quantity() == 1).toList();
        assertThat(adds).extracting(s -> s.reply().status()).containsOnly(200);
        long purchased = sent.stream().filter(s -> s.quantity() == -1 && s.reply().status() == 200).count();
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(2 + adds.size() - purchased);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'purchase'", sku))
                .isEqualTo(purchased);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ? AND reason = 'add'", sku))
                .isEqualTo(adds.size() + 1L);
        assertThat(keyRows()).as("only the /v2 requests store a key").isEqualTo(THREADS / 2);
    }
}
