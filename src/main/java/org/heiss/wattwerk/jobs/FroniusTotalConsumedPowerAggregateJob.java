package org.heiss.wattwerk.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;

@ApplicationScoped
public class FroniusTotalConsumedPowerAggregateJob {
    private static final Logger LOG = Logger.getLogger(FroniusTotalConsumedPowerAggregateJob.class);
    private static final String JOB_LOCK_NAME = "wattwerk:fronius-total-consumed-power-aggregate";
    private static final String LOCK_SQL_RESOURCE = "/db/jobs/acquire-job-lock.sql";
    private static final String DELETE_SQL_RESOURCE = "/db/jobs/delete-aggregate-window.sql";
    private static final String AGGREGATION_SQL_RESOURCE = "/db/jobs/fronius-total-consumed-power-aggregate.sql";

    private final DataSource dataSource;
    private final TotalConsumedPowerAggregateConfig config;
    private final String lockSql;
    private final String deleteSql;
    private final String aggregationSql;

    @Inject
    public FroniusTotalConsumedPowerAggregateJob(
            DataSource dataSource,
            TotalConsumedPowerAggregateConfig config) {
        this.dataSource = dataSource;
        this.config = config;
        this.lockSql = readSql(LOCK_SQL_RESOURCE);
        this.deleteSql = readSql(DELETE_SQL_RESOURCE);
        this.aggregationSql = readSql(AGGREGATION_SQL_RESOURCE);
    }

    @Transactional
    @Scheduled(every = "1m", identity = "fronius-total-consumed-power-aggregate", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        long startedAt = System.nanoTime();
        try {
            validateConfiguration();

            try (Connection connection = dataSource.getConnection()) {
                if (!acquireJobLock(connection)) {
                    LOG.debug("Skipping Fronius total-consumed power aggregation; another instance holds the job lock");
                    return;
                }

                int deletedRows = deleteExistingWindow(connection);
                AggregationResult result = calculateAndStoreAggregates(connection);
                validateAggregationResult(result);
                logCompletion(result, deletedRows, startedAt);
            }
        } catch (SQLException e) {
            LOG.error("Fronius total-consumed power aggregation failed", e);
            throw new IllegalStateException("Fronius total-consumed power aggregation failed", e);
        } catch (RuntimeException e) {
            LOG.error("Fronius total-consumed power aggregation failed", e);
            throw e;
        }
    }

    private boolean acquireJobLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(lockSql)) {
            statement.setString(1, JOB_LOCK_NAME);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Job lock query did not return a result");
                }
                return results.getBoolean(1);
            }
        }
    }

    private int deleteExistingWindow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setString(1, config.output().series());
            statement.setLong(2, config.lookback().toSeconds());
            statement.setLong(3, config.outputBucket().toSeconds());
            return statement.executeUpdate();
        }
    }

    private AggregationResult calculateAndStoreAggregates(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(aggregationSql)) {
            statement.setString(1, config.source().solarItem());
            statement.setString(2, config.source().gridItem());
            statement.setString(3, config.source().batteryItem());
            statement.setString(4, config.source().unit());
            statement.setString(5, config.output().series());
            statement.setString(6, config.output().unit());
            statement.setLong(7, config.lookback().toSeconds());
            statement.setLong(8, config.sourceBucket().toSeconds());
            statement.setLong(9, config.outputBucket().toSeconds());

            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Aggregation query did not return its row counts");
                }
                return new AggregationResult(
                        results.getLong("source_rows"),
                        results.getLong("solar_samples"),
                        results.getLong("grid_samples"),
                        results.getLong("battery_samples"),
                        results.getLong("aligned_samples"),
                        results.getLong("invalid_unit_rows"),
                        results.getLong("written_rows"),
                        results.getObject("range_start", OffsetDateTime.class),
                        results.getObject("range_stop", OffsetDateTime.class));
            }
        }
    }

    private void validateConfiguration() {
        if (config.source().solarItem().equals(config.source().gridItem())
                || config.source().solarItem().equals(config.source().batteryItem())
                || config.source().gridItem().equals(config.source().batteryItem())) {
            throw new IllegalStateException("Solar, grid, and battery item names must be distinct");
        }
        validateWholeSecondDuration("lookback", config.lookback());
        long sourceBucketSeconds = validateWholeSecondDuration("source bucket", config.sourceBucket());
        long outputBucketSeconds = validateWholeSecondDuration("output bucket", config.outputBucket());
        if (outputBucketSeconds % sourceBucketSeconds != 0) {
            throw new IllegalStateException("Output bucket must be a multiple of the source bucket");
        }
    }

    private long validateWholeSecondDuration(String name, Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds <= 0 || !Duration.ofSeconds(seconds).equals(duration)) {
            throw new IllegalStateException(name + " must be a positive whole number of seconds");
        }
        return seconds;
    }

    private void validateAggregationResult(AggregationResult result) {
        if (result.solarSamples() == 0 || result.gridSamples() == 0 || result.batterySamples() == 0
                || result.alignedSamples() == 0 || result.invalidUnitRows() > 0) {
            throw new IllegalStateException(
                    "Required source series are missing, unaligned, or have unexpected units; refusing to replace existing aggregates");
        }
    }

    private void logCompletion(AggregationResult result, int deletedRows, long startedAt) {
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        LOG.infov(
                "Completed Fronius total-consumed power aggregation: sourceRange=[{0}, {1}), sourceRows={2}, alignedSamples={3}, deletedRows={4}, writtenRows={5}, durationMs={6}",
                result.rangeStart(),
                result.rangeStop(),
                result.sourceRows(),
                result.alignedSamples(),
                deletedRows,
                result.writtenRows(),
                durationMillis);
    }

    private String readSql(String resource) {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing SQL resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read SQL resource: " + resource, e);
        }
    }

    private record AggregationResult(
            long sourceRows,
            long solarSamples,
            long gridSamples,
            long batterySamples,
            long alignedSamples,
            long invalidUnitRows,
            long writtenRows,
            OffsetDateTime rangeStart,
            OffsetDateTime rangeStop) {
    }
}
