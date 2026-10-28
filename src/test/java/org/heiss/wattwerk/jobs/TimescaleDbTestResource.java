package org.heiss.wattwerk.jobs;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.Map;

public class TimescaleDbTestResource implements QuarkusTestResourceLifecycleManager {
    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.17.2-pg16")
            .asCompatibleSubstituteFor("postgres");

    private final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(TIMESCALE_IMAGE)
            .withDatabaseName("wattwerk")
            .withUsername("wattwerk")
            .withPassword("test-only-password");

    @Override
    public Map<String, String> start() {
        database.start();
        try (var connection = DriverManager.getConnection(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
            }
            TestSqlScripts.execute(connection, "db/test/create-source-tables.sql");
        } catch (Exception e) {
            database.stop();
            throw new IllegalStateException("Could not initialize the TimescaleDB test database", e);
        }

        return Map.of(
                "quarkus.datasource.jdbc.url", database.getJdbcUrl(),
                "quarkus.datasource.username", database.getUsername(),
                "quarkus.datasource.password", database.getPassword(),
                "quarkus.scheduler.enabled", "false");
    }

    @Override
    public void stop() {
        database.stop();
    }
}
