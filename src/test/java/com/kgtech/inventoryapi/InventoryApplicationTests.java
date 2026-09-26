package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/** AC1: context loads against Testcontainers Postgres, Flyway applied, Hibernate validation on. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryApplicationTests {

    @Autowired
    Flyway flyway;

    @Autowired
    Environment environment;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LocalContainerEntityManagerFactoryBean entityManagerFactory;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayAppliedV1AndV2Successfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).hasSize(2);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getDescription()).isEqualTo("inventory");
        assertThat(applied[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(applied[1].getDescription()).isEqualTo("idempotency");
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
    }

    @Test
    void hibernateDdlAutoIsValidate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        // the value Hibernate actually receives, not just the configured property
        assertThat(entityManagerFactory.getJpaPropertyMap()).containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    void migrationsCreateLedgerAndIdempotencyTables() {
        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """)
                .query(String.class)
                .list();

        assertThat(tables)
                .containsExactlyInAnyOrder("sku", "inventory_ledger", "idempotency_keys", "flyway_schema_history");
    }
}
