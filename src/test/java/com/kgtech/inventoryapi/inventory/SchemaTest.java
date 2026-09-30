package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.IntegrationTest;

/** The constraints and triggers of V1 and V5, executed against Postgres (S11, DESIGN-V2 §1, C-10, A14). */
@IntegrationTest
class SchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    static Stream<Arguments> rejectedRows() {
        return Stream.of(
                Arguments.of("negative quantity", "INSERT INTO sku (sku_id, quantity) VALUES ('s', -1)", "23514"),
                Arguments.of("direct ledger insert", "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) "
                        + "VALUES ('seeded', 1, 'add')", "P0001"),
                Arguments.of("update ledger", "UPDATE inventory_ledger SET quantity_delta = 2", "P0001"),
                Arguments.of("delete ledger", "DELETE FROM inventory_ledger", "P0001"),
                Arguments.of("delete sku", "DELETE FROM sku WHERE sku_id = 'seeded'", "P0001"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRows")
    void postgresRejects(String what, String sql, String sqlState) {
        Tables.seed(jdbc, "seeded", 5);

        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .extracting(e -> NestedExceptionUtils.getMostSpecificCause(e))
                .isInstanceOfSatisfying(SQLException.class, sql1 -> assertThat(sql1.getSQLState()).isEqualTo(sqlState));
        assertThat(jdbc.sql("SELECT quantity FROM sku WHERE sku_id = 'seeded'").query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isEqualTo(1);
    }

    static Stream<Arguments> ledgerRowsTheTriggerNeverWrites() {
        return Stream.of(
                Arguments.of("zero delta", "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) "
                        + "VALUES ('seeded', 0, 'add')", "23514"),
                Arguments.of("unknown reason", "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) "
                        + "VALUES ('seeded', 1, 'refund')", "23514"),
                Arguments.of("unknown sku", "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) "
                        + "VALUES ('ghost', 1, 'add')", "23503"));
    }

    /**
     * V5's guard refuses every direct ledger insert first, so V1's CHECK and foreign key are reached here with the guard
     * disabled inside a transaction that is rolled back. They still stop such a row if the guard is bypassed (A14).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ledgerRowsTheTriggerNeverWrites")
    void ledgerConstraintsHoldWithTheGuardDisabled(String what, String sql, String sqlState) throws SQLException {
        Tables.seed(jdbc, "seeded", 5);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("ALTER TABLE inventory_ledger DISABLE TRIGGER inventory_ledger_only_from_balance");
                assertThatThrownBy(() -> statement.executeUpdate(sql))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
            } finally {
                connection.rollback();
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_ledger").query(Long.class).single()).isEqualTo(1);
    }

    /** TRUNCATE is how tests reset; the triggers must not block it (C-19). */
    @Test
    void truncateIsAllowed() {
        Tables.seed(jdbc, "seeded", 5);
        Tables.reset(jdbc);
        assertThat(jdbc.sql("SELECT count(*) FROM sku").query(Long.class).single()).isZero();
    }

    /** G11, W1: COLLATE "C" orders by code point, so uppercase sorts before lowercase and ABC != abc. */
    @Test
    void skuIdOrdersByCCollation() {
        for (String id : List.of("b", "B", "a", "A", "a.1", "a-1", "a_1")) {
            Tables.seed(jdbc, id, 1);
        }
        List<String> ordered = jdbc.sql("SELECT sku_id FROM sku ORDER BY sku_id").query(String.class).list();
        assertThat(ordered).containsExactly("A", "B", "a", "a-1", "a.1", "a_1", "b");
    }
}
