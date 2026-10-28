DELETE FROM public.items;
DELETE FROM public.item_meta;

INSERT INTO public.item_meta (id, name) VALUES
    (1, 'FroniusSymo_Solar_Power'),
    (2, 'FroniusSymo_Grid_Power'),
    (3, 'FroniusSymo_Battery_Power');

WITH samples AS (
    SELECT
        date_trunc('second', transaction_timestamp() - INTERVAL '7 minutes') AS battery_limited_time,
        date_trunc('second', transaction_timestamp() - INTERVAL '2 minutes') AS battery_included_time
)
INSERT INTO public.items (time, item_id, value, unit, downsampled)
SELECT battery_limited_time, reading.item_id, reading.value, 'W', false
FROM samples
CROSS JOIN (VALUES
    (1::bigint, 100.0::double precision),
    (2::bigint, 50.0::double precision),
    (3::bigint, -200.0::double precision)
) AS reading(item_id, value)
UNION ALL
SELECT battery_included_time, reading.item_id, reading.value, 'W', false
FROM samples
CROSS JOIN (VALUES
    (1::bigint, 1000.0::double precision),
    (2::bigint, 100.0::double precision),
    (3::bigint, -200.0::double precision)
) AS reading(item_id, value);
