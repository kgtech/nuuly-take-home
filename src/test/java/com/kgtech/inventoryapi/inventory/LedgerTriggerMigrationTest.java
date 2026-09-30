package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * A14: V5 installs the ledger trigger only on a database whose balances already equal their ledger sums, so it never
 * carries existing drift forward. Runs Flyway itself on a scratch database inside the shared container: to V4, a
 * balance with no ledger row, then to the latest version.
 */
@IntegrationTest
class LedgerTriggerMigrationTest {

    private static final String SCRATCH = "v5_migration_scratch";
    private static final Pattern DATABASE = Pattern.compile("^(jdbc:postgresql://[^/]+/)([^?]*)(.*)$");

    @Autowired
    JdbcClient jdbc;

    private static Map<String, String> connection() {
        Map<String, String> properties = Stream.of(TestcontainersConfiguration.connectionProperties())
                .map(entry -> entry.split("=", 2))
                .collect(Collectors.toMap(entry -> entry[0], entry -> entry[1]));
        String url = properties.get("spring.datasource.url");
        properties.put("url", DATABASE.matcher(url).replaceFirst("$1" + SCRATCH + "$3"));
        return properties;
    }

    private static Flyway flyway(Map<String, String> connection, String target) {
        return Flyway.configure()
                .dataSource(connection.get("url"), connection.get("spring.datasource.username"),
                        connection.get("spring.datasource.password"))
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void v5RefusesADriftedDatabaseAndInstallsTheTriggerOnAConsistentOne() {
        Map<String, String> connection = connection();
        jdbc.sql("DROP DATABASE IF EXISTS " + SCRATCH + " WITH (FORCE)").update();
        jdbc.sql("CREATE DATABASE " + SCRATCH).update();
        try {
            flyway(connection, "4").migrate();
            JdbcClient scratch = JdbcClient.create(new DriverManagerDataSource(connection.get("url"),
                    connection.get("spring.datasource.username"), connection.get("spring.datasource.password")));
            scratch.sql("INSERT INTO sku (sku_id, quantity) VALUES ('drifted', 5)").update();

            assertThatThrownBy(() -> flyway(connection, "latest").migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("differs from the ledger sum");
            assertThat(scratch.sql("SELECT count(*) FROM pg_trigger WHERE tgname = 'sku_balance_recorded_on_update'")
                    .query(Long.class).single()).as("the failed V5 rolled back").isZero();

            scratch.sql("INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES ('drifted', 5, 'add')")
                    .update();
            flyway(connection, "latest").migrate();

            assertThat(flyway(connection, "latest").info().applied())
                    .extracting(info -> info.getVersion().getVersion()).contains("5");
            scratch.sql("UPDATE sku SET quantity = quantity - 2 WHERE sku_id = 'drifted'").update();
            assertThat(scratch.sql("SELECT SUM(quantity_delta)::bigint FROM inventory_ledger WHERE sku_id = 'drifted'")
                    .query(Long.class).single()).isEqualTo(3);
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS " + SCRATCH + " WITH (FORCE)").update();
        }
    }
}
