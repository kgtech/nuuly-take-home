package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A14 (V5): Postgres writes the ledger row whenever sku.quantity changes and refuses any other ledger insert, so the
 * balance and the ledger sum can't drift apart through the API or a hand-written statement.
 */
@IntegrationTest
class LedgerTriggerTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    StockRepository stock;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    private List<String> ledger(String skuId) {
        return jdbc.sql("SELECT quantity_delta || ' ' || reason FROM inventory_ledger WHERE sku_id = ? ORDER BY id")
                .param(skuId).query(String.class).list();
    }

    /** A connection of its own (no pool), so session state such as a temporary table never reaches another test. */
    private Connection ownConnection() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        return DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
    }

    @Test
    void anAddAndAPurchaseWriteOneLedgerRowEach() {
        assertThat(stock.add("a", 10)).isPresent();
        assertThat(stock.purchase("a", 3)).isPresent();
        assertThat(stock.purchase("a", 50)).as("insufficient: no row updated").isEmpty();

        assertThat(ledger("a")).containsExactly("10 add", "-3 purchase");
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void aDirectLedgerInsertIsRefused() {
        Tables.seed(jdbc, "a", 5);

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) "
                + "VALUES ('a', 100, 'add')").update())
                .extracting(NestedExceptionUtils::getMostSpecificCause)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("P0001"));
        assertThat(ledger("a")).containsExactly("5 add");
    }

    @Test
    void aHandWrittenBalanceChangeIsRecorded() {
        Tables.seed(jdbc, "a", 5);

        jdbc.sql("UPDATE sku SET quantity = 999 WHERE sku_id = 'a'").update();
        jdbc.sql("UPDATE sku SET quantity = 0 WHERE sku_id = 'a'").update();

        assertThat(ledger("a")).containsExactly("5 add", "994 add", "-999 purchase");
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void aRowWhoseQuantityDoesNotChangeWritesNoLedgerRow() {
        Tables.seed(jdbc, "a", 5);

        jdbc.sql("UPDATE sku SET quantity = quantity WHERE sku_id = 'a'").update();
        jdbc.sql("UPDATE sku SET version = version + 1 WHERE sku_id = 'a'").update();
        jdbc.sql("INSERT INTO sku (sku_id) VALUES ('empty')").update();

        assertThat(ledger("a")).containsExactly("5 add");
        assertThat(ledger("empty")).isEmpty();
    }

    @Test
    void anInsertWithStockIsRecordedAsAnAdd() {
        jdbc.sql("INSERT INTO sku (sku_id, quantity) VALUES ('b', 5)").update();

        assertThat(ledger("b")).containsExactly("5 add");
    }

    /** The functions pin search_path, so a session's temporary inventory_ledger can't capture the row. */
    @Test
    void aTemporaryLedgerTableDoesNotCaptureTheRow() throws SQLException {
        Tables.seed(jdbc, "a", 5);
        try (Connection connection = ownConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE inventory_ledger (sku_id text, quantity_delta bigint, reason text)");
            statement.executeUpdate("UPDATE sku SET quantity = quantity + 5 WHERE sku_id = 'a'");
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_temp.inventory_ledger")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
        }
        assertThat(ledger("a")).containsExactly("5 add", "5 add");
    }

    /** The UPDATE trigger has no column list, so a quantity change another BEFORE trigger makes is recorded too. */
    @Test
    void aQuantityChangedByAnotherTriggerIsRecorded() throws SQLException {
        Tables.seed(jdbc, "a", 5);
        try (Connection connection = ownConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE FUNCTION test_bump() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN NEW.quantity := NEW.quantity + 100; RETURN NEW; END $$");
                statement.execute("CREATE TRIGGER test_bump BEFORE UPDATE ON sku FOR EACH ROW "
                        + "EXECUTE FUNCTION test_bump()");
                statement.executeUpdate("UPDATE sku SET version = version + 1 WHERE sku_id = 'a'");
                try (ResultSet rows = statement.executeQuery("SELECT string_agg(quantity_delta || ' ' || reason, ', ' "
                        + "ORDER BY id) FROM inventory_ledger WHERE sku_id = 'a'")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("5 add, 100 add");
                }
            } finally {
                connection.rollback();
            }
        }
        assertThat(ledger("a")).containsExactly("5 add");
    }
}
