package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.HttpHeaders.LINK;
import static org.springframework.http.HttpHeaders.TRANSFER_ENCODING;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jayway.jsonpath.JsonPath;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Z3, R4, G10, S5 through real Tomcat: a GET /inventory query string that can't be decoded (a malformed
 * percent-escape or invalid UTF-8) answers 400 text/plain "Invalid request", never 500. MockMvc doesn't decode the
 * query and java.net.URI rejects these escapes, so the request is written to a raw socket. Not @Transactional: the
 * server commits its own transactions, so the tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class InventoryListMalformedQueryIntegrationTest {

    private static final List<String> SEEDED = List.of("A-1", "B-2", "C-3");
    private static final int TIMEOUT_MILLIS = 30_000;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private record RawResponse(int status, HttpHeaders headers, String body) {

        MediaType contentType() {
            return MediaType.parseMediaType(headers.getFirst(CONTENT_TYPE));
        }
    }

    @BeforeEach
    void seed() {
        // test-only deletes; the application never deletes ledger or sku rows (G5)
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
        for (String skuId : SEEDED) {
            jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(skuId).update();
            jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, 5, 'add')")
                    .param(skuId).update();
        }
    }

    /** Sends {@code GET /inventory?<query>} byte for byte, so the server sees the escapes exactly as written. */
    private RawResponse get(String query) throws IOException {
        String request = "GET /inventory?" + query + " HTTP/1.1\r\n"
                + HOST + ": localhost:" + port + "\r\n"
                + ACCEPT + ": " + APPLICATION_JSON_VALUE + "\r\n"
                + CONNECTION + ": close\r\n"
                + "\r\n";
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    private static RawResponse parse(byte[] raw) throws IOException {
        int headerEnd = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        assertThat(headerEnd).as("end of headers in %s", new String(raw, StandardCharsets.ISO_8859_1))
                .isNotNegative();
        String[] lines = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        assertThat(statusLine[0]).as(lines[0]).startsWith("HTTP/1.");
        int status = Integer.parseInt(statusLine[1]);
        HttpHeaders headers = new HttpHeaders();
        for (String line : Arrays.asList(lines).subList(1, lines.length)) {
            int colon = line.indexOf(':');
            headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        byte[] body = Arrays.copyOfRange(raw, headerEnd + 4, raw.length);
        if ("chunked".equalsIgnoreCase(headers.getFirst(TRANSFER_ENCODING))) {
            body = dechunk(body);
        } else if (headers.containsHeader(CONTENT_LENGTH)) {
            int length = Integer.parseInt(headers.getFirst(CONTENT_LENGTH));
            assertThat(body.length).as("body bytes").isGreaterThanOrEqualTo(length);
            body = Arrays.copyOf(body, length);
        }
        return new RawResponse(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] chunked) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        int pos = 0;
        while (true) {
            int lineEnd = indexOf(chunked, crlf, pos);
            assertThat(lineEnd).as("chunk size line").isNotNegative();
            String sizeLine = new String(chunked, pos, lineEnd - pos, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(sizeLine.split(";", 2)[0].trim(), 16);
            if (size == 0) {
                return out.toByteArray();
            }
            out.write(chunked, lineEnd + 2, size);
            pos = lineEnd + 2 + size + 2;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = from; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }

    /** Control: the raw helper reads a normal paged response (chunked or not) through the same path. */
    @Test
    void validQueryReturnsJsonPage() throws IOException {
        RawResponse response = get("limit=2");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.contentType().isCompatibleWith(APPLICATION_JSON)).as(response.toString()).isTrue();
        List<String> skuIds = JsonPath.read(response.body(), "$[*].skuId");
        assertThat(skuIds).containsExactly("A-1", "B-2");
        assertThat(response.headers().getFirst(LINK))
                .isEqualTo("<http://localhost:" + port + "/inventory?limit=2&after=B-2>; rel=\"next\"");
    }

    /** Z3: a malformed escape or invalid UTF-8 anywhere in the query is a client error, not a 500. */
    @ParameterizedTest(name = "?{0} → 400")
    @ValueSource(strings = {
        "after=%zz",
        "after=%",
        "after=a%",
        "after=%FF",
        "after=%C3%28",
        "limit=%zz",
        "limit=2&after=%E2%82",
        "foo=%zz"
    })
    void undecodableQueryReturns400(String query) throws IOException {
        RawResponse response = get(query);

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo("Invalid request");
    }

    /** Z3: a repeated after is rejected over real HTTP too. */
    @Test
    void repeatedAfterReturns400() throws IOException {
        RawResponse response = get("after=A-1&after=B-2");

        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).isEqualTo("Invalid request");
    }
}
