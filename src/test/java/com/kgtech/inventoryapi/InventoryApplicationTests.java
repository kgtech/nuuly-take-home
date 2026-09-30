package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

/**
 * The context loads against Testcontainers Postgres with every migration applied (D5, E3), on a JDBC-only persistence
 * stack: no JPA, Spring Data or Hibernate ORM, and a JDBC transaction manager (E2). Hibernate Validator stays for
 * {@code @Valid} (D1).
 */
@IntegrationTest
class InventoryApplicationTests {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayAppliedV1ToV3() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).extracting(info -> info.getVersion().getVersion()).containsExactly("1", "2", "3");
        assertThat(applied).extracting(MigrationInfo::getDescription)
                .containsExactly("inventory", "idempotency", "balance row");
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
    }

    /** E2: named, not referenced, so the test compiles without them; Hibernate Validator is not Hibernate ORM. */
    @Test
    void noJpaOrHibernateOrmOnTheClasspath() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(Stream.of("jakarta.persistence.EntityManager", "org.hibernate.SessionFactory",
                        "org.springframework.data.repository.Repository",
                        "org.springframework.orm.jpa.JpaTransactionManager")
                .filter(name -> ClassUtils.isPresent(name, loader)))
                .as("JPA, Hibernate ORM and Spring Data classes on the classpath")
                .isEmpty();
        assertThat(ClassUtils.isPresent("org.hibernate.validator.HibernateValidator", loader))
                .as("Hibernate Validator (@Valid)").isTrue();
    }

    /** E2: JdbcClient and TransactionTemplate share the DataSource's transactions. */
    @Test
    void transactionManagerIsJdbc() {
        assertThat(transactionManager).isInstanceOf(DataSourceTransactionManager.class);
    }

    @Test
    void tablesAreExactlyTheThree() {
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
