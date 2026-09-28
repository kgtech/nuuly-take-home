package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.kgtech.inventoryapi.TestDatabase;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/** AC2, the V1 schema shape and the V3 append-only triggers (C4), executed against Postgres (S11). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerSchemaTest {

    private static final String RESTRICT_VIOLATION = "23001";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String STRING_TOO_LONG = "22001";
    private static final String GENERATED_ALWAYS = "428C9";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        TestDatabase.truncateAll(jdbc, transactionManager);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void insertSku(String skuId) {
        jdbc.sql("INSERT INTO sku (sku_id) VALUES (?)").param(skuId).update();
    }

    private void insertLedger(String skuId, Long delta, String reason) {
        jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, ?)")
                .params(skuId, delta, reason)
                .update();
    }

    private void assertSqlState(ThrowingCallable call, String sqlState, String constraintOrNull) {
        assertThatThrownBy(call).satisfies(thrown -> assertSqlState(thrown, sqlState, constraintOrNull));
    }

    private static void assertSqlState(Throwable thrown, String sqlState, String constraintOrNull) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).as("SQLState of: %s", sql.getMessage()).isEqualTo(sqlState);
            if (constraintOrNull != null) {
                assertThat(sql.getMessage()).contains(constraintOrNull);
            }
        });
    }

    /**
     * AC2, D5: each row is one invalid ledger insert. sku column: seeded = insert a fresh sku first, unknown = a fresh
     * id never inserted, NULL = a null sku_id.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", delimiter = '|', textBlock = """
            zero delta      | seeded  | 0    | add    | 23514 | inventory_ledger_quantity_delta_check
            unknown reason  | seeded  | 1    | refund | 23514 | inventory_ledger_reason_check
            reason case     | seeded  | 1    | Add    | 23514 | inventory_ledger_reason_check
            empty reason    | seeded  | 1    | ''     | 23514 | inventory_ledger_reason_check
            null reason     | seeded  | 1    | NULL   | 23502 | NULL
            null delta      | seeded  | NULL | add    | 23502 | NULL
            null sku_id     | NULL    | 1    | add    | 23502 | NULL
            unknown sku     | unknown | 1    | add    | 23503 | inventory_ledger_sku_id_fkey
            """)
    void rejectsInvalidLedgerRow(String name, String skuMode, Long delta, String reason, String sqlState,
            String constraint) {
        String sku = switch (skuMode) {
            case null -> null;
            case "seeded" -> {
                String id = "row-" + suffix();
                insertSku(id);
                yield id;
            }
            case "unknown" -> "ghost-" + suffix();
            default -> throw new IllegalArgumentException(skuMode);
        };

        assertSqlState(() -> insertLedger(sku, delta, reason), sqlState, constraint);

        if (sku != null) {
            assertThat(ledgerRows(sku)).isZero();
        }
    }

    /**
     * AC1, G5, V1 (C4): UPDATE, DELETE and TRUNCATE on inventory_ledger and sku fail with 23001, with and without a
     * matching row (including a sku with no ledger rows, which the foreign key does not protect), and leave the balance
     * unchanged. The WHERE false rows pin the statement-level trigger: a statement that matches no row fails too. Each
     * statement runs once; the thrown exception is then checked.
     */
    @ParameterizedTest(name = "{0} (ledger row: {1})")
    @CsvSource(delimiter = '|', textBlock = """
            UPDATE inventory_ledger SET quantity_delta = -999 WHERE sku_id = :sku      | true
            DELETE FROM inventory_ledger WHERE sku_id = :sku                           | true
            DELETE FROM sku WHERE sku_id = :sku                                        | true
            DELETE FROM sku WHERE sku_id = :sku                                        | false
            UPDATE sku SET created_at = now() WHERE sku_id = :sku                      | false
            UPDATE inventory_ledger SET quantity_delta = 1 WHERE false AND :sku = :sku | false
            DELETE FROM sku WHERE false AND :sku = :sku                                | false
            TRUNCATE inventory_ledger                                                  | true
            TRUNCATE sku CASCADE                                                       | true
            """)
    void appendOnlyTablesRejectUpdateDeleteAndTruncate(String statement, boolean withLedgerRow) {
        String sku = "append-" + suffix();
        insertSku(sku);
        if (withLedgerRow) {
            insertLedger(sku, 5L, "add");
        }
        Throwable thrown = catchThrowable(() -> jdbc.sql(statement).param("sku", sku).update());

        assertThat(thrown).as("exception from: %s", statement).isInstanceOf(DataIntegrityViolationException.class);
        assertSqlState(thrown, RESTRICT_VIOLATION, "append-only");

        Long skuRows = jdbc.sql("SELECT count(*) FROM sku WHERE sku_id = ?").param(sku).query(Long.class).single();
        assertThat(skuRows).isEqualTo(1L);
        assertThat(ledgerRows(sku)).isEqualTo(withLedgerRow ? 1L : 0L);
        assertThat(balance(sku)).isEqualTo(withLedgerRow ? 5L : 0L);
    }

    /**
     * C4: TestDatabase.truncateAll bypasses the triggers only inside its own transaction (SET LOCAL). Afterwards the
     * pooled connection, reused on this thread, is back to the origin role and the triggers fire again.
     */
    @Test
    void truncateAllLeavesNoReplicaRoleBehind() {
        String sku = "wipe-" + suffix();
        insertSku(sku);
        insertLedger(sku, 5L, "add");
        int pidBefore = backendPid();

        TestDatabase.truncateAll(jdbc, transactionManager);

        assertThat(backendPid()).as("same pooled connection").isEqualTo(pidBefore);
        assertThat(jdbc.sql("SHOW session_replication_role").query(String.class).single()).isEqualTo("origin");
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isZero();
        assertSqlState(() -> jdbc.sql("UPDATE inventory_ledger SET quantity_delta = 1 WHERE false").update(),
                RESTRICT_VIOLATION, "append-only");
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private long ledgerRows(String sku) {
        return jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?").param(sku).query(Long.class).single();
    }

    private long balance(String sku) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?")
                .param(sku)
                .query(Long.class)
                .single();
    }

    @Test
    void acceptsAddAndPurchaseRows() {
        String sku = "ok-" + suffix();
        assertThatCode(() -> {
            insertSku(sku);
            insertLedger(sku, 5L, "add");
            insertLedger(sku, -3L, "purchase");
        }).doesNotThrowAnyException();

        Long balance = jdbc.sql("SELECT COALESCE(SUM(quantity_delta), 0)::bigint FROM inventory_ledger WHERE sku_id = ?")
                .param(sku)
                .query(Long.class)
                .single();
        assertThat(balance).isEqualTo(2L);

        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT id, reason, created_at FROM inventory_ledger WHERE sku_id = ? ORDER BY id")
                .param(sku)
                .query()
                .listOfRows();
        assertThat(rows).extracting(row -> row.get("reason")).containsExactly("add", "purchase");
        long firstId = ((Number) rows.get(0).get("id")).longValue();
        long secondId = ((Number) rows.get(1).get("id")).longValue();
        assertThat(firstId).isPositive();
        assertThat(secondId).isGreaterThan(firstId);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("created_at")).isNotNull());

        Object skuCreatedAt = jdbc.sql("SELECT created_at FROM sku WHERE sku_id = ?")
                .param(sku)
                .query()
                .singleValue();
        assertThat(skuCreatedAt).isNotNull();
    }

    @Test
    void acceptsBigintExtremeDeltas() {
        String maxSku = "max-" + suffix();
        String minSku = "min-" + suffix();

        assertThatCode(() -> {
            insertSku(maxSku);
            insertLedger(maxSku, Long.MAX_VALUE, "add");
            insertSku(minSku);
            insertLedger(minSku, Long.MIN_VALUE, "purchase");
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsExplicitLedgerId() {
        String sku = "explicit-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> jdbc.sql(
                                "INSERT INTO inventory_ledger (id, sku_id, quantity_delta, reason) VALUES (?, ?, ?, ?)")
                        .params(999_999_999L, sku, 1L, "add")
                        .update(),
                GENERATED_ALWAYS, null);
    }

    @Test
    void rejectsDuplicateSkuId() {
        String sku = "dup-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertSku(sku), UNIQUE_VIOLATION, "sku_pkey");
    }

    @Test
    void skuIdIsCaseSensitive() {
        String s = suffix();

        assertThatCode(() -> {
            insertSku("ABC" + s);
            insertSku("abc" + s);
        }).doesNotThrowAnyException();
    }

    @Test
    void skuIdAllowsSixtyFourCharsAndRejectsSixtyFive() {
        String s = suffix();
        String sixtyFour = s + "x".repeat(64 - s.length());
        String sixtyFive = s + "y".repeat(65 - s.length());

        assertThatCode(() -> insertSku(sixtyFour)).doesNotThrowAnyException();
        assertSqlState(() -> insertSku(sixtyFive), STRING_TOO_LONG, null);
    }

    @Test
    void skuIdOrdersByCCollation() {
        String s = suffix();
        String lowerB = "b" + s;
        String upperB = "B" + s;
        String lowerA = "a" + s;
        String upperZ = "Z" + s;

        assertThatCode(() -> {
            insertSku(lowerB);
            insertSku(upperB);
            insertSku(lowerA);
            insertSku(upperZ);
        }).doesNotThrowAnyException();

        List<String> ordered = jdbc.sql("SELECT sku_id FROM sku WHERE sku_id IN (?, ?, ?, ?) ORDER BY sku_id")
                .params(lowerB, upperB, lowerA, upperZ)
                .query(String.class)
                .list();
        assertThat(ordered).containsExactly(upperB, upperZ, lowerA, lowerB);
    }

    @Test
    void skuIdColumnsUseCCollationAndVarchar64() {
        List<Map<String, Object>> columns = jdbc.sql("""
                SELECT table_name, data_type, character_maximum_length, collation_name
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

    private Map<String, Map<String, Object>> columnsOf(String table) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT column_name, data_type, is_nullable, column_default, is_identity, identity_generation
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
}
