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
