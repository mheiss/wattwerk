WITH job_config AS (
    SELECT
        ?::text AS output_series,
        ?::bigint * INTERVAL '1 second' AS lookback,
        ?::bigint * INTERVAL '1 second' AS output_bucket
),
bounds AS (
    SELECT
        date_trunc('hour', transaction_timestamp() - cfg.lookback) AS range_start,
        transaction_timestamp() AS range_stop,
        cfg.output_bucket
    FROM job_config AS cfg
)
DELETE FROM wattwerk.aggregated
USING job_config, bounds
WHERE series = job_config.output_series
  AND bucket_time >= time_bucket(bounds.output_bucket, bounds.range_start) + bounds.output_bucket
  AND bucket_time <= time_bucket(
        bounds.output_bucket,
        bounds.range_stop - INTERVAL '1 microsecond'
  ) + bounds.output_bucket
