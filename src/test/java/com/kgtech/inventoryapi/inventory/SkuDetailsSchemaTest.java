package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
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

/** Issue #71 AC1: the V3 migration's constraints and trigger, executed against Postgres (S11). */
@IntegrationTest
class SkuDetailsSchemaTest {

    private static final String INSERT = "INSERT INTO sku_details (sku_id, name, description, cost_amount, "
            + "cost_currency, images) VALUES ";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
        Tables.seed(jdbc, "seeded", 5);
        jdbc.sql(INSERT + "('seeded', 'Shirt', '', 100, 'USD', '{}')").update();
    }

    static Stream<Arguments> rejectedRows() {
        String tenPlusOne = "'{" + "a,".repeat(10) + "b}'";
        return Stream.of(
                Arguments.of("unknown sku", INSERT + "('ghost', 'n', '', NULL, NULL, '{}')", "23503"),
                Arguments.of("empty name", INSERT + "('other', '', '', NULL, NULL, '{}')", "23514"),
                Arguments.of("blank name", INSERT + "('other', '   ', '', NULL, NULL, '{}')", "23514"),
                Arguments.of("121-char name", INSERT + "('other', repeat('n', 121), '', NULL, NULL, '{}')", "23514"),
                Arguments.of("2001-char description", INSERT + "('other', 'n', repeat('d', 2001), NULL, NULL, '{}')",
                        "23514"),
                Arguments.of("negative cost", INSERT + "('other', 'n', '', -1, 'USD', '{}')", "23514"),
                Arguments.of("lowercase currency", INSERT + "('other', 'n', '', 1, 'usd', '{}')", "23514"),
                Arguments.of("amount without currency", INSERT + "('other', 'n', '', 1, NULL, '{}')", "23514"),
                Arguments.of("currency without amount", INSERT + "('other', 'n', '', NULL, 'USD', '{}')", "23514"),
                Arguments.of("11 images", INSERT + "('other', 'n', '', NULL, NULL, " + tenPlusOne + ")", "23514"),
                Arguments.of("version 0", "UPDATE sku_details SET version = 0 WHERE sku_id = 'seeded'", "23514"),
                Arguments.of("delete details", "DELETE FROM sku_details WHERE sku_id = 'seeded'", "P0001"),
                Arguments.of("unknown key operation", "INSERT INTO idempotency_keys (idempotency_key, operation, "
                        + "sku_id, request_hash) VALUES (gen_random_uuid(), 'replace', 's', decode(repeat('00', 32), "
                        + "'hex'))", "23514"),
                Arguments.of("key status 202", "INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, "
                        + "request_hash, status, content_type, body) VALUES (gen_random_uuid(), 'create', 's', "
                        + "decode(repeat('00', 32), 'hex'), 202, 'text/plain', 'x')", "23514"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRows")
    void postgresRejects(String what, String sql, String sqlState) {
        Tables.seed(jdbc, "other", 1);

        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .extracting(e -> NestedExceptionUtils.getMostSpecificCause(e))
                .isInstanceOfSatisfying(SQLException.class, s -> assertThat(s.getSQLState()).isEqualTo(sqlState));
        assertThat(jdbc.sql("SELECT count(*) FROM sku_details").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void acceptsTheBoundaryValuesAndTheCreateKeyStatuses() {
        Tables.seed(jdbc, "edge", 1);
        jdbc.sql(INSERT + "('edge', repeat('n', 120), repeat('d', 2000), 0, 'EUR', '{"
                + "a,b,c,d,e,f,g,h,i,j}')").update();
        for (int status : new int[] {201, 409}) {
            jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, operation, sku_id, request_hash, status, "
                    + "content_type, body) VALUES (gen_random_uuid(), 'create', 'edge', decode(repeat('00', 32), "
                    + "'hex'), ?, 'text/plain', 'x')").param(status).update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single()).isEqualTo(2);
    }

    /** TRUNCATE (the test reset) must clear details too, and the trigger must not block it. */
    @Test
    void resetClearsDetails() {
        Tables.reset(jdbc);
        assertThat(jdbc.sql("SELECT count(*) FROM sku_details").query(Long.class).single()).isZero();
    }
}
