package com.mercala;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests. Starts a single ParadeDB Postgres container
 * (shared across all subclasses, since the container is {@code static}) and wires
 * the Spring datasource to it via {@link ServiceConnection}. Flyway then runs the
 * migrations against this real database — so tests exercise the actual schema path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("paradedb/paradedb:latest")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("mercala")
                    .withUsername("mercala")
                    .withPassword("mercala");
}
