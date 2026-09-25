package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/** The V2 idempotency_keys shape and constraints, executed against Postgres (S3, S8, S11, Y4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencySchemaTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTables() {
        // test-only delete; the application never purges keys (R9)
        jdbc.sql("DELETE FROM idempotency_keys").update();
    }

    private static byte[] hash(int length) {
        return new byte[length];
    }

    private void insert(UUID key, String operation, String skuId, byte[] hash, Integer status, String contentType,
            String body) {
        jdbc.sql("""
                INSERT INTO idempotency_keys
                    (idempotency_key, operation, sku_id, request_hash, status, content_type, body)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(key, operation, skuId, hash, status, contentType, body)
                .update();
    }

    private void insertClaim(UUID key) {
        insert(key, "add", "widget", hash(32), null, null, null);
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single();
    }

    private void assertSqlState(ThrowingCallable call, String sqlState, String constraint) {
        assertThatThrownBy(call).satisfies(thrown -> {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
            assertThat(cause).isInstanceOfSatisfying(SQLException.class, sql -> {
                assertThat(sql.getSQLState()).as("SQLState of: %s", sql.getMessage()).isEqualTo(sqlState);
                assertThat(sql.getMessage()).contains(constraint);
            });
        });
    }

    @Test
    void duplicateKeyViolatesPrimaryKey() {
        UUID key = UUID.randomUUID();
        assertThatCode(() -> insertClaim(key)).doesNotThrowAnyException();

        assertSqlState(() -> insert(key, "purchase", "gadget", hash(32), null, null, null),
                UNIQUE_VIOLATION, "idempotency_keys_pkey");
        assertThat(rows()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"refund", "Add", "''"})
    void unknownOperationViolatesCheck(String operation) {
        assertSqlState(() -> insert(UUID.randomUUID(), operation, "widget", hash(32), null, null, null),
                CHECK_VIOLATION, "idempotency_keys_operation_check");
    }

    @ParameterizedTest
    @CsvSource({"0", "16", "31", "33", "64"})
    void hashNot32BytesViolatesCheck(int length) {
        assertSqlState(() -> insert(UUID.randomUUID(), "add", "widget", hash(length), null, null, null),
                CHECK_VIOLATION, "idempotency_keys_request_hash_check");
    }

    @ParameterizedTest
    @CsvSource({"201", "409", "500", "0"})
    void status201ViolatesCheck(int status) {
        assertSqlState(() -> insert(UUID.randomUUID(), "add", "widget", hash(32), status, "text/plain", "x"),
                CHECK_VIOLATION, "idempotency_keys_status_check");
    }

    /** Y4 (refined): the response columns are all NULL (claimed) or all set (completed), never partly set. */
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "200,  application/json, NULL",
        "200,  NULL,             body",
        "NULL, text/plain,       body",
        "NULL, NULL,             body",
        "404,  NULL,             NULL",
        "NULL, text/plain,       NULL"
    })
    void partialResponseViolatesCompleteCheck(Integer status, String contentType, String body) {
        assertSqlState(() -> insert(UUID.randomUUID(), "add", "widget", hash(32), status, contentType, body),
                CHECK_VIOLATION, "idempotency_response_complete");
        assertThat(rows()).isZero();
    }

    @Test
    void claimedAndCompletedRowsAreAccepted() {
        UUID key = UUID.randomUUID();
        assertThatCode(() -> {
            insertClaim(key);
            insert(UUID.randomUUID(), "purchase", "widget", hash(32), 404, "text/plain", "SKU not found");
            insert(UUID.randomUUID(), "add", "widget", hash(32), 400, "text/plain", "Invalid request");
            insert(UUID.randomUUID(), "add", "widget", hash(32), 200, "application/json",
                    "{\"skuId\":\"widget\",\"quantity\":5}");
        }).doesNotThrowAnyException();

        // the claim is completed by an UPDATE in the same transaction (R2, Y4)
        int updated = jdbc.sql("""
                UPDATE idempotency_keys SET status = 200, content_type = 'application/json', body = '{}'
                WHERE idempotency_key = ? AND status IS NULL
                """).param(key).update();
        assertThat(updated).isEqualTo(1);
        assertThat(rows()).isEqualTo(4);
    }

    @Test
    void createdAtDefaultsToNow() {
        UUID key = UUID.randomUUID();
        insertClaim(key);

        Boolean recent = jdbc.sql("""
                SELECT created_at <= now() AND created_at > now() - interval '1 minute'
                FROM idempotency_keys WHERE idempotency_key = ?
                """).param(key).query(Boolean.class).single();
        assertThat(recent).isTrue();

        Map<String, Object> createdAt = columnsOf("idempotency_keys").get("created_at");
        assertThat(createdAt.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(createdAt.get("is_nullable")).isEqualTo("NO");
        assertThat(createdAt.get("column_default")).isEqualTo("now()");
    }

    /** Plan OQ3: no foreign key to sku, so a 404 for a SKU that does not exist can be stored. */
    @Test
    void skuIdNeedsNoSkuRow() {
        String sku = "ghost-" + UUID.randomUUID().toString().substring(0, 8);

        assertThatCode(() -> insert(UUID.randomUUID(), "purchase", sku, hash(32), 404, "text/plain", "SKU not found"))
                .doesNotThrowAnyException();
        assertThat(jdbc.sql("SELECT count(*) FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single())
                .isZero();
    }

    /** S3: the key column is uuid, so upper- and lowercase text forms are the same key. */
    @Test
    void uuidMatchesCaseInsensitively() {
        String lower = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash) "
                        + "VALUES (?::uuid, 'add', 'widget', ?)")
                .params(lower, hash(32))
                .update();

        Long found = jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?::uuid")
                .param(lower.toUpperCase())
                .query(Long.class)
                .single();
        assertThat(found).isEqualTo(1);
        assertSqlState(() -> jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash) "
                                + "VALUES (?::uuid, 'add', 'widget', ?)")
                        .params(lower.toUpperCase(), hash(32))
                        .update(),
                UNIQUE_VIOLATION, "idempotency_keys_pkey");
    }

    @Test
    void columnTypes() {
        Map<String, Map<String, Object>> columns = columnsOf("idempotency_keys");

        assertThat(columns.keySet()).containsExactlyInAnyOrder("idempotency_key", "operation", "sku_id",
                "request_hash", "status", "content_type", "body", "created_at");
        assertThat(columns.get("idempotency_key").get("data_type")).isEqualTo("uuid");
        assertThat(columns.get("operation").get("data_type")).isEqualTo("text");
        assertThat(columns.get("operation").get("is_nullable")).isEqualTo("NO");
        assertThat(columns.get("sku_id").get("data_type")).isEqualTo("character varying");
        assertThat(((Number) columns.get("sku_id").get("character_maximum_length")).intValue()).isEqualTo(64);
        assertThat(columns.get("sku_id").get("collation_name")).isEqualTo("C");
        assertThat(columns.get("sku_id").get("is_nullable")).isEqualTo("NO");
        assertThat(columns.get("request_hash").get("data_type")).isEqualTo("bytea");
        assertThat(columns.get("request_hash").get("is_nullable")).isEqualTo("NO");
        assertThat(columns.get("status").get("data_type")).isEqualTo("smallint");
        assertThat(columns.get("content_type").get("data_type")).isEqualTo("text");
        assertThat(columns.get("body").get("data_type")).isEqualTo("text");
        for (String nullable : List.of("status", "content_type", "body")) {
            assertThat(columns.get(nullable).get("is_nullable")).as(nullable).isEqualTo("YES");
        }
    }

    private Map<String, Map<String, Object>> columnsOf(String table) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT column_name, data_type, is_nullable, column_default, character_maximum_length, collation_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """)
                .param(table)
                .query()
                .listOfRows();
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byName.put((String) row.get("column_name"), row);
        }
        return byName;
    }
}
