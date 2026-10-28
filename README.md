# WattWerk

Quarkus application for home energy analytics. It reads raw readings persisted by openHAB, calculates derived time-series values, and stores them in TimescaleDB.

## Database layout and data safety

The database has three time-series hypertables:

- `public.items` — openHAB's raw readings, partitioned by `time`.
- `wattwerk.aggregated` — short-window calculated values, retained for five years.
- `wattwerk.history` — longer-term calculated energy, cost, and revenue values, retained indefinitely.

The `openhab` schema provides read-only views `openhab.items` and `openhab.item_meta` over the existing `public` tables. These are query aliases, not hypertables: `public.items` remains the openHAB hypertable, and `public.item_meta` remains a regular table.

All openHAB data is read-only to WattWerk. The application reads through `openhab.items` and `openhab.item_meta`; writes through these views are rejected. Application code must never insert, update, delete, truncate, or alter the underlying `public` tables or their schema, indexes, hypertable configuration, or retention. WattWerk writes calculated values only to its own `wattwerk` schema.

Liquibase runs the versioned database setup at application startup to create WattWerk's output hypertables and configure their retention; the TimescaleDB extension must already be installed and enabled.

## Scheduled jobs

### Total consumed power aggregation

- **Schedule:** Every minute.
- **Reads:** Non-downsampled readings from `openhab.items`, joined with `openhab.item_meta`, for the configured solar, grid, and battery items. Defaults are `FroniusSymo_Solar_Power`, `FroniusSymo_Grid_Power`, and `FroniusSymo_Battery_Power`; the configured source unit defaults to `W`. The default lookback is 12 hours, beginning at the hour boundary in `Europe/Vienna`.
- **Computes:** Five-second mean values for each input series, aligns timestamps, then calculates total consumed power as solar plus grid, adding battery power unless `battery power × -1` exceeds solar power. It keeps positive results and calculates their mean in each five-minute window. Output timestamps are the ends of those windows.
- **Writes:** The configured output series (default `FroniusSymo_TotalConsumed_Power`) and unit (default `W`) to `wattwerk.aggregated`. Each run replaces its overlapping output range so late or corrected readings are reflected without duplicate rows.

Configure the names and units with `wattwerk.jobs.total-consumed-power-aggregate.source.solar-item`, `source.grid-item`, `source.battery-item`, `source.unit`, `output.series`, and `output.unit` properties (all using the full `wattwerk.jobs.total-consumed-power-aggregate` prefix). The `lookback` (default `12H`), `source-bucket` (default `5S`), and `output-bucket` (default `5M`) properties control the calculation and replacement window together; durations must be positive whole seconds, and the output bucket must be a multiple of the source bucket.

## Dev Environment

Run `./gradlew prepareDevConfig` to copy the samples files from the `sample` directory to the `dev` directory. 
Files in `dev` can be modified as required.

### Docker database

Start the local TimescaleDB instance with `docker compose -f dev/docker/database.yaml up -d`. On the first start with an empty `wattwerk-dev-data` volume, Docker runs `dev/docker/db-init/001-source-tables.sql`. It enables TimescaleDB, creates local-only `public.items` and `public.item_meta` source tables, and registers the three configured Fronius item names. The database starts without readings; add synthetic local readings if you want to exercise the aggregation job.

Docker runs init scripts only when it initializes an empty data volume. Restarting the container preserves the database and does not rerun them. Removing the Compose volume with `docker compose -f docker/database.yaml down -v` permanently deletes this local database and its data.

### Quarkus Application

Run `./gradlew quarkusDev` to apply Liquibase and start the app. Liquibase creates the WattWerk output tables and read-only `openhab` views if required.

## Testing

Run `./gradlew test`. The job integration test starts an isolated TimescaleDB container with Testcontainers, so Docker must be running. It creates minimal `public.items` and `public.item_meta` test tables in that disposable database, applies the normal Liquibase changelog, and seeds synthetic readings from `src/test/resources/db/test/seed-total-consumed-power.sql`. It verifies both battery calculation cases and confirms that running the job again does not duplicate output rows. No local or production openHAB database is used.
