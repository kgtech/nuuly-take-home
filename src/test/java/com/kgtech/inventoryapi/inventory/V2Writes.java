package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the /v2 stock-write tests (H2, H10): the two keyed POSTs' paths, texts and replies, the request hashes as
 * the database stores them, and a real-HTTP client for the concurrency tests.
 */
final class V2Writes {

    static final String INVALID_REQUEST = "Invalid request";
    static final String INSUFFICIENT_INVENTORY = "Insufficient inventory";
    static final String SKU_NOT_FOUND = "SKU not found";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private V2Writes() {
    }

    /** The two keyed /v2 POSTs; dbValue is the idempotency row's operation and the ledger reason. */
    enum Op {
        ADD("/v2/inventory/{skuId}", "/v2/inventory/%s", "add"),
        PURCHASE("/v2/inventory/{skuId}/purchase", "/v2/inventory/%s/purchase", "purchase");

        final String template;
        final String pathFormat;
        final String dbValue;

        Op(String template, String pathFormat, String dbValue) {
            this.template = template;
            this.pathFormat = pathFormat;
            this.dbValue = dbValue;
        }

        String path(String skuId) {
            return pathFormat.formatted(skuId);
        }
    }

    /** One HTTP response: status, the exact Content-Type header and the body. */
    record Reply(int status, String contentType, String body) {
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    static String quantityJson(long quantity) {
        return "{\"quantity\":" + quantity + "}";
    }

    /** H10: SHA-256 of "v2" + "\n" + operation + "\n" + skuId + "\n" + quantity, as request_hash holds it. */
    static byte[] v2Hash(Op op, String skuId, long quantity) {
        return sha256("v2\n" + op.dbValue + "\n" + skuId + "\n" + quantity);
    }

    /** The unversioned form (A33): SHA-256 of operation + "\n" + skuId + "\n" + quantity. */
    static byte[] unversionedHash(Op op, String skuId, long quantity) {
        return sha256(op.dbValue + "\n" + skuId + "\n" + quantity);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A 200 reply is a SkuItem without details: exactly skuId and quantity, as JSON. Returns the quantity. */
    static long itemQuantity(Reply reply, String skuId) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(reply.contentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode item = JsonMapper.shared().readTree(reply.body());
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("skuId", "quantity");
        assertThat(item.get("skuId").asString()).isEqualTo(skuId);
        return item.get("quantity").asLong();
    }

    /** POST over real HTTP; a null key sends no Idempotency-Key header. */
    static Reply post(HttpClient http, int port, String path, String body, String key)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),
                response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""), response.body());
    }
}
