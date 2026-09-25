package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/** AC2 and the V1 schema shape, executed against Postgres (S11). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerSchemaTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String STRING_TOO_LONG = "22001";
    private static final String GENERATED_ALWAYS = "428C9";

    @Autowired
    JdbcClient jdbc;

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
        assertThatThrownBy(call).satisfies(thrown -> {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
            assertThat(cause).isInstanceOfSatisfying(SQLException.class, sql -> {
                assertThat(sql.getSQLState()).as("SQLState of: %s", sql.getMessage()).isEqualTo(sqlState);
                if (constraintOrNull != null) {
                    assertThat(sql.getMessage()).contains(constraintOrNull);
                }
            });
        });
    }

    @Test
    void rejectsZeroQuantityDelta() {
        String sku = "zero-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertLedger(sku, 0L, "add"), CHECK_VIOLATION, "inventory_ledger_quantity_delta_check");

        Long count = jdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?")
                .param(sku)
                .query(Long.class)
                .single();
        assertThat(count).isZero();
    }

    @Test
    void rejectsUnknownReason() {
        String sku = "refund-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertLedger(sku, 1L, "refund"), CHECK_VIOLATION, "inventory_ledger_reason_check");
    }

    @Test
    void rejectsReasonWithDifferentCase() {
        String sku = "case-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertLedger(sku, 1L, "Add"), CHECK_VIOLATION, "inventory_ledger_reason_check");
    }

    @Test
    void rejectsEmptyReason() {
        String sku = "empty-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertLedger(sku, 1L, ""), CHECK_VIOLATION, "inventory_ledger_reason_check");
    }

    @Test
    void rejectsNullReason() {
        String sku = "nullreason-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertLedger(sku, 1L, null), NOT_NULL_VIOLATION, null);
    }

    @Test
    void rejectsNullQuantityDelta() {
        String sku = "nulldelta-" + suffix();
        assertThatCode(() -> insertSku(sku)).doesNotThrowAnyException();

        assertSqlState(() -> insertLedger(sku, null, "add"), NOT_NULL_VIOLATION, null);
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
    void rejectsLedgerRowForUnknownSku() {
        String sku = "ghost-" + suffix();

        assertSqlState(() -> insertLedger(sku, 1L, "add"), FOREIGN_KEY_VIOLATION, "inventory_ledger_sku_id_fkey");
    }

    @Test
    void rejectsNullLedgerSkuId() {
        assertSqlState(() -> insertLedger(null, 1L, "add"), NOT_NULL_VIOLATION, null);
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
