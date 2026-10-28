WITH job_config AS (
    SELECT
        ?::text AS solar_item_name,
        ?::text AS grid_item_name,
        ?::text AS battery_item_name,
        ?::text AS source_unit,
        ?::text AS output_series,
        ?::text AS output_unit,
        ?::bigint * INTERVAL '1 second' AS lookback,
        ?::bigint * INTERVAL '1 second' AS source_bucket,
        ?::bigint * INTERVAL '1 second' AS output_bucket
),
bounds AS (
    SELECT
        date_trunc('hour', transaction_timestamp() - cfg.lookback) AS range_start,
        transaction_timestamp() AS range_stop
    FROM job_config AS cfg
),
source_samples AS (
    SELECT
        time_bucket(cfg.source_bucket, i.time) + cfg.source_bucket AS sample_time,
        m.name AS item_name,
        AVG(i.value) AS power_w,
        COUNT(*) AS source_rows,
        COUNT(*) FILTER (WHERE i.unit IS DISTINCT FROM cfg.source_unit) AS invalid_unit_rows
    FROM openhab.items AS i
    JOIN openhab.item_meta AS m ON m.id = i.item_id
    CROSS JOIN bounds AS b
    CROSS JOIN job_config AS cfg
    WHERE m.name IN (cfg.solar_item_name, cfg.grid_item_name, cfg.battery_item_name)
      AND i.downsampled = FALSE
      AND i.value IS NOT NULL
      AND i.time >= b.range_start
      AND i.time < b.range_stop
    GROUP BY time_bucket(cfg.source_bucket, i.time), m.name, cfg.source_unit, cfg.source_bucket
),
aligned_power AS (
    SELECT
        sample_time,
        MAX(power_w) FILTER (WHERE item_name = (SELECT solar_item_name FROM job_config)) AS solar_w,
        MAX(power_w) FILTER (WHERE item_name = (SELECT grid_item_name FROM job_config)) AS grid_w,
        MAX(power_w) FILTER (WHERE item_name = (SELECT battery_item_name FROM job_config)) AS battery_w,
        COUNT(*) AS present_series
    FROM source_samples
    GROUP BY sample_time
),
total_consumed_power AS (
    SELECT
        sample_time,
        CASE
            WHEN battery_w * -1.0 > solar_w THEN solar_w + grid_w
            ELSE solar_w + grid_w + battery_w
        END AS power_w
    FROM aligned_power
    WHERE present_series = 3
      AND solar_w IS NOT NULL
      AND grid_w IS NOT NULL
      AND battery_w IS NOT NULL
),
output_bucket_averages AS (
    SELECT
        time_bucket(cfg.output_bucket, sample_time) + cfg.output_bucket AS bucket_time,
        AVG(power_w) AS value
    FROM total_consumed_power
    CROSS JOIN job_config AS cfg
    WHERE power_w > 0.0
    GROUP BY time_bucket(cfg.output_bucket, sample_time) + cfg.output_bucket
),
upserted AS (
    INSERT INTO wattwerk.aggregated (bucket_time, series, value, unit)
    SELECT
        bucket_time,
        cfg.output_series,
        value,
        cfg.output_unit
    FROM output_bucket_averages
    CROSS JOIN job_config AS cfg
    ON CONFLICT (bucket_time, series) DO UPDATE
    SET value = EXCLUDED.value,
        unit = EXCLUDED.unit
    RETURNING 1
)
SELECT
    COALESCE((SELECT SUM(source_rows) FROM source_samples), 0)::bigint AS source_rows,
    (SELECT COUNT(*) FROM source_samples WHERE item_name = (SELECT solar_item_name FROM job_config))::bigint AS solar_samples,
    (SELECT COUNT(*) FROM source_samples WHERE item_name = (SELECT grid_item_name FROM job_config))::bigint AS grid_samples,
    (SELECT COUNT(*) FROM source_samples WHERE item_name = (SELECT battery_item_name FROM job_config))::bigint AS battery_samples,
    COALESCE((SELECT SUM(invalid_unit_rows) FROM source_samples), 0)::bigint AS invalid_unit_rows,
    (SELECT COUNT(*) FROM total_consumed_power)::bigint AS aligned_samples,
    (SELECT COUNT(*) FROM upserted)::bigint AS written_rows,
    (SELECT range_start FROM bounds) AS range_start,
    (SELECT range_stop FROM bounds) AS range_stop
