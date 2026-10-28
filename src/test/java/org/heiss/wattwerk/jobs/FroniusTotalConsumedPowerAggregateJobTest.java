package org.heiss.wattwerk.jobs;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import jakarta.inject.Inject;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@QuarkusTestResource(TimescaleDbTestResource.class)
class FroniusTotalConsumedPowerAggregateJobTest {
    @Inject
    FroniusTotalConsumedPowerAggregateJob job;

    @Inject
    DataSource dataSource;

    @Test
    void aggregatesBothBatteryCasesAndIsIdempotent() throws Exception {
        seed("db/test/seed-total-consumed-power.sql");

        job.run();
        job.run();

        assertEquals(List.of(
                new OutputRow(150.0, "W"),
                new OutputRow(1100.0, "W")), readOutputRows());
        assertEquals(2, countOutputRows());
    }

    private void seed(String script) throws Exception {
        try (var connection = dataSource.getConnection()) {
            TestSqlScripts.execute(connection, script);
        }
    }

    private List<OutputRow> readOutputRows() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT value, unit
                     FROM wattwerk.aggregated
                     WHERE series = ?
                     ORDER BY bucket_time
                     """)) {
            statement.setString(1, "FroniusSymo_TotalConsumed_Power");
            try (ResultSet results = statement.executeQuery()) {
                List<OutputRow> values = new ArrayList<>();
                while (results.next()) {
                    values.add(new OutputRow(results.getDouble("value"), results.getString("unit")));
                }
                return values;
            }
        }
    }

    private long countOutputRows() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM wattwerk.aggregated
                     WHERE series = ?
                     """)) {
            statement.setString(1, "FroniusSymo_TotalConsumed_Power");
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
        }
    }

    private record OutputRow(double value, String unit) {
    }
}
