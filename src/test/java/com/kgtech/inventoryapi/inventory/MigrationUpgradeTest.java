package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * E3 (closes #24's upgrade path): an existing v1 database, migrated to V2 and holding v1 ledger rows and stored
 * Idempotency-Keys, moves to the balance row through V3. Each test runs programmatic Flyway against its own fresh
 * database in the shared container (clean disabled), and drops it afterwards. The app's context is used only to create
 * and drop those databases.
 */
@IntegrationTest
class MigrationUpgradeTest {

    private static final String KEY_ROWS = """
            SELECT idempotency_key, operation, sku_id, encode(request_hash, 'hex') AS request_hash,
                   status, content_type, body, created_at
            FROM idempotency_keys ORDER BY idempotency_key
            """;

    @Autowired
    JdbcClient jdbc;

    private final List<String> databases = new ArrayList<>();

    @AfterEach
    void dropDatabases() {
        for (String database : databases) {
            jdbc.sql("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)").update();
        }
    }

    private DataSource newDatabase() {
        String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE DATABASE " + database).update();
        databases.add(database);
        return new DriverManagerDataSource(TestcontainersConfiguration.jdbcUrl(database),
                TestcontainersConfiguration.username(), TestcontainersConfiguration.password());
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .target(target)
                .load();
    }

    private static void ledger(JdbcClient db, String skuId, long... deltas) {
        db.sql("INSERT INTO sku (sku_id) VALUES (?)").param(skuId).update();
        for (long delta : deltas) {
            db.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (?, ?, ?)")
                    .params(skuId, delta, delta > 0 ? "add" : "purchase")
                    .update();
        }
    }

    /** A key row as v1 stored it: request_hash is SHA-256 of operation, skuId and quantity (Y3). */
    private static void storedKey(JdbcClient db, String operation, String skuId, int quantity, int status,
            String contentType, String body) {
        db.sql("""
                INSERT INTO idempotency_keys
                    (idempotency_key, operation, sku_id, request_hash, status, content_type, body)
                VALUES (?, ?, ?, sha256(convert_to(? || E'\\n' || ? || E'\\n' || ?, 'UTF8')), ?, ?, ?)
                """)
                .params(UUID.randomUUID(), operation, skuId, operation, skuId, Integer.toString(quantity), status,
                        contentType, body)
                .update();
    }

    private static boolean v3Succeeded(Flyway flyway) {
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null && info.getVersion().getVersion().equals("3")) {
                return info.getState() == MigrationState.SUCCESS;
            }
        }
        return false;
    }

    private static long count(JdbcClient db, String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    @Test
    void v3BackfillsBalancesAndKeepsStoredKeys() {
        DataSource dataSource = newDatabase();
        flyway(dataSource, "2").migrate();
        JdbcClient db = JdbcClient.create(dataSource);
        ledger(db, "ABC-1", 5, 7, -4);
        ledger(db, "ZERO", 3, -3);
        ledger(db, "BARE");
        ledger(db, "BIG", 9_223_372_036_854_775_000L, 807);
        ledger(db, "abc-1", 2);
        storedKey(db, "add", "ABC-1", 5, 200, "application/json", "{\"skuId\":\"ABC-1\",\"quantity\":5}");
        storedKey(db, "purchase", "NOPE", 1, 404, "text/plain", "SKU not found");
        List<Map<String, Object>> keysBefore = db.sql(KEY_ROWS).query().listOfRows();

        Flyway latest = flyway(dataSource, "latest");
        latest.migrate();

        assertThat(v3Succeeded(latest)).as("V3 applied successfully").isTrue();
        Invariants.assertBalanceColumns(db);
        List<String> balances = db.sql("""
                SELECT sku_id || '=' || quantity || '/' || version FROM sku ORDER BY sku_id
                """).query(String.class).list();
        assertThat(balances).containsExactly("ABC-1=8/3", "BARE=0/0", "BIG=9223372036854775807/2", "ZERO=0/2",
                "abc-1=2/1");
        assertThat(Invariants.balanceMismatches(db)).as("quantity = SUM(quantity_delta)").isEmpty();
        assertThat(db.sql(KEY_ROWS).query().listOfRows()).as("idempotency_keys rows").isEqualTo(keysBefore);
        assertThatThrownBy(() -> db.sql("UPDATE inventory_ledger SET quantity_delta = quantity_delta").update())
                .extracting(e -> NestedExceptionUtils.getMostSpecificCause(e))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("P0001"));
    }

    /** E3: Flyway runs V3 in one transaction, so a negative ledger SUM fails the CHECK and nothing of V3 remains. */
    @Test
    void v3FailsAtomicallyOnANegativeLedgerSum() {
        DataSource dataSource = newDatabase();
        flyway(dataSource, "2").migrate();
        JdbcClient db = JdbcClient.create(dataSource);
        ledger(db, "NEG", -1);

        assertThatThrownBy(() -> flyway(dataSource, "latest").migrate())
                .isInstanceOf(FlywayException.class)
                .extracting(e -> NestedExceptionUtils.getMostSpecificCause(e))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));

        assertThat(count(db, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'sku' AND column_name IN ('quantity', 'version')
                """)).as("V3 columns").isZero();
        assertThat(count(db, "SELECT count(*) FROM pg_proc WHERE proname = 'forbid_row_change'"))
                .as("forbid_row_change()").isZero();
        assertThat(count(db, "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success"))
                .as("successful V3 row").isZero();
        assertThat(count(db, "SELECT count(*) FROM inventory_ledger")).as("ledger rows").isEqualTo(1);
    }
}
