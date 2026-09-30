package com.kgtech.inventoryapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres container per JVM, shared by every Spring context (DESIGN-V2 tests, C-20). The bean has no destroy
 * method, so a closing context never stops the container another context still uses; Ryuk removes it when the JVM
 * exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE_PROPERTY = "inventory.test.postgres-image";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(image(POSTGRES_IMAGE_PROPERTY)));
    static {
        POSTGRES.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    /**
     * Connection properties for a second application instance started outside the test context (a plain
     * SpringApplication must not import this configuration: Boot's Testcontainers lifecycle would stop the shared
     * containers when that instance closes).
     */
    public static String[] connectionProperties() {
        return new String[] {
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
        };
    }

    private static String image(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(property + " is not set; run tests through Gradle");
        }
        return image;
    }
}
