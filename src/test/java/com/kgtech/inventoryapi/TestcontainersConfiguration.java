package com.kgtech.inventoryapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared Postgres container for every database test (D9). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String IMAGE_PROPERTY = "inventory.test.postgres-image";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        String image = System.getProperty(IMAGE_PROPERTY);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(IMAGE_PROPERTY + " is not set; run tests through Gradle");
        }
        return new PostgreSQLContainer(DockerImageName.parse(image));
    }
}
