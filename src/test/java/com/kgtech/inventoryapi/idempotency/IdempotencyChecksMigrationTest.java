package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * H9: V4 narrows the idempotency CHECKs V3 widened for build v2's create (operation 'create', statuses 201 and 409)
 * back to add|purchase and 200|400|404, with NOT VALID, so a database that already holds such rows migrates and reads
 * them while Postgres refuses new ones. Runs Flyway itself on a scratch database inside the shared container: to V3,
 * legacy rows, then to the latest version.
 */
@IntegrationTest
class IdempotencyChecksMigrationTest {

    private static final String SCRATCH = "v4_migration_scratch";
    private static final Pattern DATABASE = Pattern.compile("^(jdbc:postgresql://[^/]+/)([^?]*)(.*)$");
    private static final String INSERT = "INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, "
            + "request_hash, status, content_type, body) VALUES (gen_random_uuid(), ?, 'legacy', "
            + "decode(repeat('00', 32), 'hex'), ?, 'text/plain', 'x')";

    @Autowired
    JdbcClient jdbc;

    private static Map<String, String> connection() {
        Map<String, String> properties = Stream.of(TestcontainersConfiguration.connectionProperties())
                .map(entry -> entry.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(entry -> entry[0], entry -> entry[1]));
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

    private static void assertRejected(JdbcClient scratch, String operation, int status) {
        assertThatThrownBy(() -> scratch.sql(INSERT).params(operation, status).update())
                .as("%s with status %d", operation, status)
                .extracting(e -> NestedExceptionUtils.getMostSpecificCause(e))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void v4NarrowsTheChecksAndKeepsLegacyRowsReadable() {
        Map<String, String> connection = connection();
        jdbc.sql("DROP DATABASE IF EXISTS " + SCRATCH + " WITH (FORCE)").update();
        jdbc.sql("CREATE DATABASE " + SCRATCH).update();
        try {
            flyway(connection, "3").migrate();
            JdbcClient scratch = JdbcClient.create(new DriverManagerDataSource(connection.get("url"),
                    connection.get("spring.datasource.username"), connection.get("spring.datasource.password")));
            scratch.sql(INSERT).params("create", 201).update();
            scratch.sql(INSERT).params("create", 409).update();

            flyway(connection, "latest").migrate();

            assertThat(flyway(connection, "latest").info().all()).extracting(info -> info.getVersion().getVersion())
                    .contains("4");
            assertThat(scratch.sql("SELECT operation || '/' || status FROM idempotency_keys ORDER BY status")
                    .query(String.class).list()).as("legacy rows survive").containsExactly("create/201", "create/409");
            assertRejected(scratch, "create", 200);
            assertRejected(scratch, "add", 201);
            assertRejected(scratch, "purchase", 409);
            assertRejected(scratch, "replace", 200);
            scratch.sql(INSERT).params("add", 200).update();
            scratch.sql(INSERT).params("purchase", 404).update();
            scratch.sql(INSERT).params("add", 400).update();
            assertThat(scratch.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single()).isEqualTo(5);
            // NOT VALID: the narrowed operation and status CHECKs are enforced for new rows but were not validated
            for (String marker : new String[] {"%''purchase''%", "%404%"}) {
                assertThat(scratch.sql("SELECT convalidated FROM pg_constraint WHERE conrelid = "
                        + "'idempotency_keys'::regclass AND contype = 'c' AND conname <> 'idempotency_response_complete' "
                        + "AND pg_get_constraintdef(oid) LIKE '" + marker + "'").query(Boolean.class).single())
                        .as("convalidated for %s", marker).isFalse();
            }
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS " + SCRATCH + " WITH (FORCE)").update();
        }
    }
}
