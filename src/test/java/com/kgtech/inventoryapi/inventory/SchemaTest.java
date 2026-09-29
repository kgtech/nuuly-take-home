package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/**
 * The migrated schema, executed against Postgres (S11, D5, E2, E3, A11): the sku and ledger columns, types, defaults,
 * collation, constraints and index, V3's balance columns and CHECK, and the append-only triggers. There is no
 * ddl-auto (E2): this test and IdempotencySchemaTest assert the schema. Replaces LedgerSchemaTest.
 */
@IntegrationTest
class SchemaTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String STRING_TOO_LONG = "22001";
    private static final String GENERATED_ALWAYS = "428C9";
    /** A11: forbid_row_change() raises P0001. */
    private static final String RAISE_EXCEPTION = "P0001";
    private static final String SEEDED = "seeded";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    // ---- helpers ----

    /** A SKU with one ledger row, written with V1's columns only. */
    private void seedLedger(String skuId, long delta) {
        insertSku(skuId);
        insertLedger(skuId, delta, delta > 0 ? "add" : "purchase");
    }

    private void insertSku(String skuId) {
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(skuId).update();
    }

    private void insertLedger(String skuId, Long delta, String reason) {
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, ?)")
                .params(skuId, delta, reason)
                .update();
    }

    private static String ledgerInsert(String skuId, String delta, String reason) {
        return "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (" + skuId + ", " + delta + ", "
                + reason + ")";
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long ledgerSum(String skuId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?")
                .param(skuId)
                .query(Long.class)
                .single();
    }

    private long quantity(String skuId) {
        return jdbc.sql("SELECT quantity FROM sku WHERE sku_id = ?").param(skuId).query(Long.class).single();
    }

    /** The statement fails with {@code sqlState}; the message names {@code messagePart} when one is given. */
    private void assertRejected(String sql, String sqlState, String messagePart) {
        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .extracting(e -> NestedExceptionUtils.getMostSpecificCause(e))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).as("SQLState of: %s", e.getMessage()).isEqualTo(sqlState);
                    if (messagePart != null) {
                        assertThat(e.getMessage()).contains(messagePart);
                    }
                });
    }

    private Map<String, Map<String, Object>> columnsOf(String table) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT column_name, data_type, is_nullable, column_default, is_identity, identity_generation,
                       character_maximum_length, collation_name
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

    // ---- columns, constraints and index ----

    /** G11: both sku_id columns are varchar(64) COLLATE "C" and NOT NULL. */
    @Test
    void skuIdColumnsUseCCollationAndVarchar64() {
        List<Map<String, Object>> columns = jdbc.sql("""
                SELECT table_name, data_type, character_maximum_length, collation_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'sku_id'
                  AND table_name IN ('sku', 'inventory_ledger')
                """)
                .query()
                .listOfRows();

        assertThat(columns).extracting(row -> row.get("table_name"))
                .containsExactlyInAnyOrder("sku", "inventory_ledger");
        assertThat(columns).allSatisfy(row -> {
            assertThat(row.get("data_type")).isEqualTo("character varying");
            assertThat(((Number) row.get("character_maximum_length")).intValue()).isEqualTo(64);
            assertThat(row.get("collation_name")).isEqualTo("C");
            assertThat(row.get("is_nullable")).isEqualTo("NO");
        });
    }

    @Test
    void ledgerColumnTypes() {
        Map<String, Map<String, Object>> ledger = columnsOf("inventory_ledger");
        Map<String, Map<String, Object>> sku = columnsOf("sku");

        assertThat(ledger).containsKeys("id", "quantity_delta", "reason", "created_at");
        assertThat(sku).containsKey("created_at");

        Map<String, Object> id = ledger.get("id");
        assertThat(id.get("data_type")).isEqualTo("bigint");
        assertThat(id.get("is_identity")).isEqualTo("YES");
        assertThat(id.get("identity_generation")).isEqualTo("ALWAYS");

        Map<String, Object> delta = ledger.get("quantity_delta");
        assertThat(delta.get("data_type")).isEqualTo("bigint");
        assertThat(delta.get("is_nullable")).isEqualTo("NO");

        Map<String, Object> reason = ledger.get("reason");
        assertThat(reason.get("data_type")).isEqualTo("text");
        assertThat(reason.get("is_nullable")).isEqualTo("NO");

        for (Map<String, Object> createdAt : List.of(ledger.get("created_at"), sku.get("created_at"))) {
            assertThat(createdAt.get("data_type")).isEqualTo("timestamp with time zone");
            assertThat(createdAt.get("is_nullable")).isEqualTo("NO");
            assertThat(createdAt.get("column_default")).isEqualTo("now()");
        }
    }

    /** E3, G2: V3 adds the balance row, quantity and version, both bigint NOT NULL DEFAULT 0, and the CHECK. */
    @Test
    void skuBalanceColumnsAndCheck() {
        Map<String, Map<String, Object>> sku = columnsOf("sku");

        assertThat(sku).as("sku columns").containsOnlyKeys("sku_id", "created_at", "quantity", "version");
        for (String name : List.of("quantity", "version")) {
            Map<String, Object> column = sku.get(name);
            assertThat(column.get("data_type")).as(name).isEqualTo("bigint");
            assertThat(column.get("is_nullable")).as(name).isEqualTo("NO");
            assertThat(column.get("column_default")).as(name).isEqualTo("0");
        }
        List<String> check = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'sku'::regclass AND conname = 'sku_quantity_check'
                """).query(String.class).list();
        assertThat(check).containsExactly("CHECK ((quantity >= 0))");
    }

    /** W2: the ledger keeps its index on sku_id. */
    @Test
    void ledgerHasSkuIdIndex() {
        List<String> indexDefs = jdbc.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'inventory_ledger'
                  AND indexname = 'inventory_ledger_sku'
                """)
                .query(String.class)
                .list();

        assertThat(indexDefs).singleElement().asString().endsWith("(sku_id)");
    }

    // ---- rows Postgres rejects ----

    /** V1's constraints: each rejected row leaves the seeded SKU, its ledger row and its balance unchanged. */
    static Stream<Arguments> v1Rejections() {
        return Stream.of(
                Arguments.of("zero delta", ledgerInsert("'seeded'", "0", "'add'"), CHECK_VIOLATION,
                        "inventory_ledger_quantity_delta_check"),
                Arguments.of("unknown reason", ledgerInsert("'seeded'", "1", "'refund'"), CHECK_VIOLATION,
                        "inventory_ledger_reason_check"),
                Arguments.of("reason in another case", ledgerInsert("'seeded'", "1", "'Add'"), CHECK_VIOLATION,
                        "inventory_ledger_reason_check"),
                Arguments.of("empty reason", ledgerInsert("'seeded'", "1", "''"), CHECK_VIOLATION,
                        "inventory_ledger_reason_check"),
                Arguments.of("null reason", ledgerInsert("'seeded'", "1", "NULL"), NOT_NULL_VIOLATION, null),
                Arguments.of("null delta", ledgerInsert("'seeded'", "NULL", "'add'"), NOT_NULL_VIOLATION, null),
                Arguments.of("unknown sku", ledgerInsert("'ghost'", "1", "'add'"), FOREIGN_KEY_VIOLATION,
                        "inventory_ledger_sku_id_fkey"),
                Arguments.of("null ledger sku_id", ledgerInsert("NULL", "1", "'add'"), NOT_NULL_VIOLATION, null),
                Arguments.of("explicit ledger id", "INSERT INTO inventory_ledger (id, sku_id, quantity_delta, reason) "
                        + "VALUES (999999999, 'seeded', 1, 'add')", GENERATED_ALWAYS, null),
                Arguments.of("duplicate sku_id", "INSERT INTO sku (sku_id) VALUES ('seeded')", UNIQUE_VIOLATION,
                        "sku_pkey"),
                Arguments.of("65 characters", "INSERT INTO sku (sku_id) VALUES ('" + "x".repeat(65) + "')",
                        STRING_TOO_LONG, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("v1Rejections")
    void postgresRejects(String what, String sql, String sqlState, String messagePart) {
        seedLedger(SEEDED, 5);

        assertRejected(sql, sqlState, messagePart);

        assertThat(count("sku")).as("sku rows").isEqualTo(1);
        assertThat(count("inventory_ledger")).as("ledger rows").isEqualTo(1);
        assertThat(ledgerSum(SEEDED)).as("ledger balance").isEqualTo(5);
    }

    /**
     * E3, A11: the balance CHECK and the append-only triggers. Each rejected statement leaves the seeded balance row and
     * ledger row unchanged; the triggers raise P0001 with "TG_OP on TG_TABLE_NAME is not allowed".
     */
    static Stream<Arguments> balanceAndTriggerRejections() {
        return Stream.of(
                Arguments.of("negative quantity", "INSERT INTO sku (sku_id, quantity) VALUES ('negative', -1)",
                        CHECK_VIOLATION, "sku_quantity_check"),
                Arguments.of("quantity below 0", "UPDATE sku SET quantity = -1 WHERE sku_id = 'seeded'",
                        CHECK_VIOLATION, "sku_quantity_check"),
                Arguments.of("update ledger", "UPDATE inventory_ledger SET quantity_delta = 2", RAISE_EXCEPTION,
                        "UPDATE on inventory_ledger is not allowed"),
                Arguments.of("delete ledger", "DELETE FROM inventory_ledger", RAISE_EXCEPTION,
                        "DELETE on inventory_ledger is not allowed"),
                Arguments.of("delete sku", "DELETE FROM sku WHERE sku_id = 'seeded'", RAISE_EXCEPTION,
                        "DELETE on sku is not allowed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("balanceAndTriggerRejections")
    void postgresRejectsBalanceAndTriggerRows(String what, String sql, String sqlState, String messagePart) {
        Invariants.assertBalanceColumns(jdbc);
        Tables.seed(jdbc, SEEDED, 5);

        assertRejected(sql, sqlState, messagePart);

        assertThat(quantity(SEEDED)).as("balance").isEqualTo(5);
        assertThat(count("sku")).as("sku rows").isEqualTo(1);
        assertThat(count("inventory_ledger")).as("ledger rows").isEqualTo(1);
    }

    // ---- rows Postgres accepts ----

    /** Add and purchase rows, bigint's extreme deltas and a 64-character skuId are all valid rows. */
    @Test
    void acceptsBoundaryRows() {
        assertThatCode(() -> {
            insertSku("ok");
            insertLedger("ok", 5L, "add");
            insertLedger("ok", -3L, "purchase");
            seedLedger("max", Long.MAX_VALUE);
            seedLedger("min", Long.MIN_VALUE);
            insertSku("x".repeat(64));
        }).doesNotThrowAnyException();

        assertThat(ledgerSum("ok")).isEqualTo(2L);
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT id, reason, created_at FROM inventory_ledger WHERE sku_id = 'ok' ORDER BY id")
                .query()
                .listOfRows();
        assertThat(rows).extracting(row -> row.get("reason")).containsExactly("add", "purchase");
        long firstId = ((Number) rows.get(0).get("id")).longValue();
        long secondId = ((Number) rows.get(1).get("id")).longValue();
        assertThat(firstId).isPositive();
        assertThat(secondId).isGreaterThan(firstId);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("created_at")).isNotNull());
        assertThat(jdbc.sql("SELECT created_at FROM sku WHERE sku_id = 'ok'").query().singleValue()).isNotNull();
    }

    /** G1: skuIds differing only in case are different rows. */
    @Test
    void skuIdIsCaseSensitive() {
        assertThatCode(() -> {
            insertSku("ABC-1");
            insertSku("abc-1");
        }).doesNotThrowAnyException();

        assertThat(count("sku")).isEqualTo(2);
    }

    /** G11: COLLATE "C" orders by code point, so uppercase sorts before lowercase. */
    @Test
    void skuIdOrdersByCCollation() {
        for (String id : List.of("b", "B", "a", "A", "a.1", "a-1", "a_1")) {
            insertSku(id);
        }

        List<String> ordered = jdbc.sql("SELECT sku_id FROM sku ORDER BY sku_id").query(String.class).list();

        assertThat(ordered).containsExactly("A", "B", "a", "a-1", "a.1", "a_1", "b");
    }

    /** E3: TRUNCATE is how tests reset (Tables.reset); the row-level triggers don't block it. */
    @Test
    void truncateIsAllowed() {
        seedLedger(SEEDED, 5);

        assertThatCode(() -> Tables.reset(jdbc)).doesNotThrowAnyException();

        assertThat(count("sku")).isZero();
        assertThat(count("inventory_ledger")).isZero();
        assertThat(count("idempotency_keys")).isZero();
    }
}
