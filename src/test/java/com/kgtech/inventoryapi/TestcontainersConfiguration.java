package com.kgtech.inventoryapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres and one Redis container per JVM, shared by every Spring context (DESIGN-V2 tests, C-20). The beans
 * have no destroy method, so a closing context never stops a container another context still uses; Ryuk removes
 * them when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE_PROPERTY = "inventory.test.postgres-image";
    public static final String REDIS_IMAGE_PROPERTY = "inventory.test.redis-image";
    public static final int REDIS_PORT = 6379;

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(image(POSTGRES_IMAGE_PROPERTY)));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(image(REDIS_IMAGE_PROPERTY)))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--appendonly", "no", "--save", "") // as compose.yaml: a cache
                    // A fixed host port, so a real `docker restart` (RedisFaultTest) keeps the address the app uses.
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(freePort()), new ExposedPort(REDIS_PORT))));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }

    /** The shared Redis container, for fault tests that pause, restart or flush it. */
    public static GenericContainer<?> redis() {
        return REDIS;
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("no free port for Redis", e);
        }
    }

    private static String image(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(property + " is not set; run tests through Gradle");
        }
        return image;
    }
}
