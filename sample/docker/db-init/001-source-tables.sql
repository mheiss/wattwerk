CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE public.item_meta (
    id bigint PRIMARY KEY,
    name text NOT NULL UNIQUE,
    label text,
    value text,
    metadata jsonb,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE TABLE public.items (
    time timestamptz NOT NULL,
    item_id bigint NOT NULL REFERENCES public.item_meta (id),
    value double precision,
    string text,
    unit text,
    downsampled boolean NOT NULL DEFAULT false
);

SELECT create_hypertable(
    'public.items',
    'time',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

INSERT INTO public.item_meta (id, name, label) VALUES
    (1, 'FroniusSymo_Solar_Power', 'Solar power'),
    (2, 'FroniusSymo_Grid_Power', 'Grid power'),
    (3, 'FroniusSymo_Battery_Power', 'Battery power');
