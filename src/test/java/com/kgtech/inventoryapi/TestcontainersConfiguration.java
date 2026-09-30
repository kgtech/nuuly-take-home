package com.kgtech.inventoryapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres container per test JVM, shared by every Spring context (D9, #24). The bean has no destroy method, so a
 * closing context never stops the container another context still uses; Ryuk removes it when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String IMAGE_PROPERTY = "inventory.test.postgres-image";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(image()));

    static {
        POSTGRES.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    /** A JDBC URL for another database in the shared container, e.g. one a migration test creates and drops. */
    public static String jdbcUrl(String database) {
        int port = POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + port + "/" + database;
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    private static String image() {
        String image = System.getProperty(IMAGE_PROPERTY);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(IMAGE_PROPERTY + " is not set; run tests through Gradle");
        }
        return image;
    }
}
