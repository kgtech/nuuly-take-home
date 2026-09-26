package com.kgtech.inventoryapi.inventory;

import static com.kgtech.inventoryapi.web.HttpConstants.IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.CONNECTION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.RawHttp;
import com.kgtech.inventoryapi.RawHttp.Response;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * C1, G11, S5, T3 through real Tomcat: a request Tomcat rejects before routing gets text/plain (400 "Invalid
 * request", or the reason phrase for other statuses), never Tomcat's HTML page, and an encoded slash in the SKU
 * reaches the controller, where SkuId.isValid rejects it (GET and purchase 404, create 400). MockMvc bypasses Tomcat,
 * so every request is written to a raw socket. Not @Transactional; the tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TomcatRejectionIntegrationTest {

    private static final String SEEDED = "ABC-1";
    private static final String QUANTITY_BODY = "{\"quantity\":1}";
    /** Package-private in inventory.web, so it is named rather than referenced. */
    private static final String TEXT_VALVE = "com.kgtech.inventoryapi.inventory.web.TextErrorReportValve";
    private static final int OVERSIZED = 10_000;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationContext context;

    @BeforeEach
    void seed() {
        // test-only deletes; the application never deletes key, ledger or sku rows (G5, R9)
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(SEEDED).update();
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, 5, 'add')")
                .param(SEEDED).update();
    }

    /** Sends {@code method target} with Host, Accept JSON and Connection: close, plus a JSON body when given. */
    private Response send(String method, String target, String body, String... extraHeaders) throws IOException {
        StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\n")
                .append(RawHttp.header(HOST, "localhost:" + port))
                .append(RawHttp.header(ACCEPT, APPLICATION_JSON_VALUE));
        for (String line : extraHeaders) {
            head.append(line);
        }
        if (body != null) {
            head.append(RawHttp.header(CONTENT_TYPE, APPLICATION_JSON_VALUE))
                    .append(RawHttp.header(CONTENT_LENGTH,
                            String.valueOf(body.getBytes(StandardCharsets.UTF_8).length)));
        }
        head.append(RawHttp.header(CONNECTION, "close"));
        return RawHttp.send(port, head.toString(), body == null ? "" : body);
    }

    private static void assertText(Response response, int status, String body) {
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.contentType()).as(response.toString()).isNotNull();
        assertThat(response.contentType().isCompatibleWith(TEXT_PLAIN)).as(response.toString()).isTrue();
        assertThat(response.body()).as(response.toString()).doesNotContain("<html").isEqualTo(body);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /** C1, G11: the encoded slash is part of the SKU ID, which fails the pattern: 404 like any unknown SKU. */
    @ParameterizedTest(name = "GET {0} → 404")
    @ValueSource(strings = {"/inventory/A%2FB", "/inventory/a%2fb", "/inventory/A%2F"})
    void encodedSlashOnGetReturnsSkuNotFound(String path) throws IOException {
        assertText(send("GET", path, null), 404, "SKU not found");
    }

    /** G11: a double-encoded slash decodes to a literal '%' in one path segment and fails the pattern: 404. */
    @Test
    void doubleEncodedSlashStaysOneSegmentAndReturnsSkuNotFound() throws IOException {
        assertText(send("GET", "/inventory/A%252FB", null), 404, "SKU not found");
    }

    @Test
    void encodedSlashOnPurchaseReturnsSkuNotFound() throws IOException {
        long ledgerRows = count("inventory_ledger");

        assertText(send("POST", "/inventory/A%2FB/purchase", QUANTITY_BODY), 404, "SKU not found");
        assertThat(count("inventory_ledger")).isEqualTo(ledgerRows);
    }

    /** G4: body validation still runs first. */
    @Test
    void encodedSlashOnPurchaseWithInvalidBodyReturnsInvalidRequest() throws IOException {
        assertText(send("POST", "/inventory/A%2FB/purchase", "{}"), 400, "Invalid request");
    }

    @Test
    void encodedSlashOnCreateReturnsInvalidRequest() throws IOException {
        assertText(send("POST", "/inventory/A%2FB", QUANTITY_BODY), 400, "Invalid request");
        assertThat(count("sku")).isEqualTo(1);
        assertThat(count("inventory_ledger")).isEqualTo(1);
    }

    /** S2, R1: the malformed-SKU 400 is not stored against the key. */
    @Test
    void encodedSlashOnKeyedCreateIsNotStored() throws IOException {
        Response response = send("POST", "/inventory/A%2FB", QUANTITY_BODY,
                RawHttp.header(IDEMPOTENCY_KEY, UUID.randomUUID().toString()));

        assertText(response, 400, "Invalid request");
        assertThat(count("idempotency_keys")).isZero();
        assertThat(count("sku")).isEqualTo(1);
        assertThat(count("inventory_ledger")).isEqualTo(1);
    }

    /** An encoded slash before the SKU segment is an unknown path. */
    @Test
    void encodedSlashBeforeSkuSegmentIsUnknownPath() throws IOException {
        assertText(send("GET", "/inventory%2FA", null), 404, "Not Found");
    }

    /** C1: escapes Tomcat can't decode, %00, %5C and illegal characters: 400 "Invalid request" on any method. */
    @ParameterizedTest(name = "{0} {1} → 400")
    @CsvSource(delimiter = '|', value = {
        "GET  | /inventory/a%zzb",
        "GET  | /inventory/a%",
        "GET  | /inventory/a%C3%28",
        "GET  | /inventory/a%FF",
        "GET  | /inventory/a%00b",
        "GET  | /inventory/a%5Cb",
        "GET  | /inventory/a{b",
        "GET  | /inventory/a b",
        "POST | /inventory/a%zzb",
        "POST | /inventory/a%",
        "POST | /inventory/a%C3%28",
        "POST | /inventory/a%FF",
        "POST | /inventory/a%00b",
        "POST | /inventory/a%5Cb",
        "POST | /inventory/a{b",
        "POST | /inventory/a%zzb/purchase"
    })
    void malformedPathReturnsInvalidRequest(String method, String path) throws IOException {
        long ledgerRows = count("inventory_ledger");

        assertText(send(method, path, "POST".equals(method) ? QUANTITY_BODY : null), 400, "Invalid request");
        assertThat(count("inventory_ledger")).isEqualTo(ledgerRows);
    }

    @Test
    void badMethodTokenReturnsInvalidRequest() throws IOException {
        assertText(send("GE(T", "/inventory/ABC-1", null), 400, "Invalid request");
    }

    @Test
    void oversizedRequestLineReturnsInvalidRequest() throws IOException {
        assertText(send("GET", "/inventory/" + repeat('a', OVERSIZED), null), 400, "Invalid request");
    }

    @Test
    void oversizedHeaderReturnsInvalidRequest() throws IOException {
        Response response = send("GET", "/inventory/" + SEEDED, null, RawHttp.header("X-Big", repeat('a', OVERSIZED)));

        assertText(response, 400, "Invalid request");
    }

    @Test
    void missingHostReturnsInvalidRequest() throws IOException {
        String head = "GET /inventory/" + SEEDED + " HTTP/1.1\r\n"
                + RawHttp.header(ACCEPT, APPLICATION_JSON_VALUE)
                + RawHttp.header(CONNECTION, "close");

        assertText(RawHttp.send(port, head, ""), 400, "Invalid request");
    }

    @Test
    void repeatedHostReturnsInvalidRequest() throws IOException {
        Response response = send("GET", "/inventory/" + SEEDED, null, RawHttp.header(HOST, "other:" + port));

        assertText(response, 400, "Invalid request");
    }

    /** C1, T3: statuses other than 400 use their reason phrase. */
    @Test
    void unsupportedHttpVersionReturnsReasonPhrase() throws IOException {
        String head = "GET /inventory/" + SEEDED + " HTTP/1.2\r\n"
                + RawHttp.header(HOST, "localhost:" + port)
                + RawHttp.header(CONNECTION, "close");

        assertText(RawHttp.send(port, head, ""), 505, HttpStatus.HTTP_VERSION_NOT_SUPPORTED.getReasonPhrase());
    }

    @Test
    void connectReturnsReasonPhrase() throws IOException {
        assertText(send("CONNECT", "localhost:" + port, null), 501, "Not Implemented");
    }

    /** C1: exactly one error report valve on the host, the text one; %2F passed through; TRACE allowed. */
    @Test
    void tomcatIsWiredForTextErrors() {
        Tomcat tomcat = ((TomcatWebServer) ((WebServerApplicationContext) context).getWebServer()).getTomcat();

        List<Valve> errorValves = Arrays.stream(tomcat.getHost().getPipeline().getValves())
                .filter(ErrorReportValve.class::isInstance)
                .toList();
        assertThat(errorValves).singleElement()
                .extracting(valve -> valve.getClass().getName())
                .isEqualTo(TEXT_VALVE);

        Connector connector = tomcat.getConnector();
        assertThat(connector.getEncodedSolidusHandling()).isEqualTo("passthrough");
        assertThat(connector.getAllowTrace()).isTrue();
    }
}
