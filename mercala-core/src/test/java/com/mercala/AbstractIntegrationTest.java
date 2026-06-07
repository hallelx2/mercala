package com.mercala;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests using the <b>singleton container</b> pattern:
 * one ParadeDB Postgres container is started once in a static initializer and reused
 * by every subclass for the whole JVM (Testcontainers' Ryuk reaps it at shutdown).
 *
 * <p>We deliberately do <em>not</em> use {@code @Testcontainers} / {@code @Container}
 * here — those manage the container lifecycle <em>per test class</em>, which would stop
 * the shared static container after the first class and leave later classes unable to
 * connect. {@link ServiceConnection} wires the Spring datasource to this container, and
 * Flyway then runs the migrations against it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("paradedb/paradedb:latest")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("mercala")
                    .withUsername("mercala")
                    .withPassword("mercala");

    static {
        POSTGRES.start();
    }
}
