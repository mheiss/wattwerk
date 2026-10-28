# Wattwerk: InfluxDB-to-TimescaleDB Migration Plan

## Project

**Name:** Wattwerk  
**Description:** Quarkus-based home energy analytics for openHAB and TimescaleDB. Reads openHAB-persisted household energy data, calculates power, energy, cost, and revenue metrics, and stores aggregates for downstream use.

## Objective and scope

Replace the InfluxDB v2 task-processing layer with a new, standalone Quarkus service named Wattwerk. openHAB already persists readings to PostgreSQL in parallel with the current InfluxDB deployment, and its JDBC persistence supports TimescaleDB hypertables. Reuse that existing PostgreSQL persistence as Wattwerk's raw-data source; Wattwerk computes the derived series and rollups currently produced by InfluxDB tasks and stores results in TimescaleDB.

Wattwerk is a separate project and deployment. It is not part of, or a module in, the existing dashboard backend. The dashboard may consume Wattwerk's stored results or a future API, but designing or implementing that integration is outside this migration plan unless separately requested.

## Current system, based on the Influx export

The exported resource file contains four bucket definitions and 37 Flux tasks. It includes task and bucket configuration, not the underlying historical time-series data or task execution history.

| Influx bucket | Current retention | Target retention | Observed role |
| --- | --- | --- | --- |
| `openhab` | 90 days | Confirm separately for the existing PostgreSQL/TimescaleDB raw readings | Raw openHAB readings and short-lived derived power/price series |
| `aggregated` | 720 days | 5 years | Short-term aggregated power and cost outputs |
| `history` | Infinite | Forever (no automatic expiry) | Daily, monthly, and yearly energy, cost, and revenue series |
| `evcc` | 90 days | Not in scope | EVCC data; no energy task in this export reads it |

Influx retention is shown as current-state context; the target values above are the agreed retention requirements for Wattwerk outputs. Keep `aggregated` and `history` in separate hypertables (or separately managed time-series tables) so their retention policies can differ, even if their schemas are similar. Configure a TimescaleDB retention policy to remove `aggregated` chunks after five years. Do not add an automatic retention policy to `history`. Confirm raw openHAB data retention separately.

### Existing calculations to preserve

The 37 tasks cover these logical stages:

1. **Fast derived power, every 15 seconds**
   - Derive total-consumption power from solar, grid, and battery power.
   - Derive direct-consumption power using solar, grid, and battery signals.

2. **Short-window series, every 1 or 5 minutes**
   - Smooth solar, grid, battery, direct-consumption, and total-consumption power.
   - Smooth battery state of charge.
   - Aggregate hourly spot-price data.
   - Prepare grid/total energy and tariff inputs used by cost calculations.

3. **Energy and cost calculations, usually hourly**
   - Convert power samples into daily energy for solar, grid import/export, battery charge/discharge, direct consumption, and total consumption.
   - Calculate hourly spot and fixed costs and notional costs.
   - Calculate daily costs and revenue.

4. **Calendar rollups**
   - Roll daily energy, cost, and revenue into monthly totals.
   - Roll monthly values into yearly totals.

Most calendar calculations run at minute 59 of each hour; some run hourly with a ten-minute offset. The frequent jobs revisit recent ranges, and several rollups recalculate the current day, month, or year. The TimescaleDB implementation must therefore support safe repeat execution and correction of prior results, rather than append-only duplicate writes.

### Semantics that need explicit parity tests

- Calendar windows use the `Europe/Vienna` time zone, including daylight-saving transitions.
- Power-to-energy conversions use different sampling intervals and divisors in different tasks. Port each formula and unit as written first; do not normalize or “correct” it without a separate decision backed by comparison.
- Some calculations combine multiple measurement streams, fill missing values with zero, filter by power sign, and apply absolute values. These choices affect the result.
- The fixed-price fallback is `0.2182`; verify its unit and intended validity.
- Feed-in revenue uses `10.168` with a configured start timestamp of `2026-09-01T00:00:00Z`; verify its unit, effective date, and time-zone intent.
- Influx measurements use the `value` field and measurement names as series identifiers. Map these concepts to the actual openHAB JDBC schema and Wattwerk tables explicitly.

## Target architecture

```text
openHAB
  ├─ JDBC persistence ──> PostgreSQL/TimescaleDB raw readings (already in operation)
  └─ InfluxDB persistence/tasks (remain during shadow comparison)

PostgreSQL/TimescaleDB raw readings
  └─> Wattwerk scheduled calculations
        └─> TimescaleDB derived/aggregate tables
              └─> downstream consumers (out of scope here)
```

- **openHAB owns ingestion and raw persistence.** Its PostgreSQL persistence is already running alongside InfluxDB; keep that path in place. Do not add a second ingestion path or a new dual-write rollout step in Wattwerk unless a verified limitation requires it.
- **TimescaleDB is PostgreSQL plus a time-series extension.** Use its standard PostgreSQL wire protocol and JDBC driver; use hypertables for suitable high-volume time-series tables, not automatically for every table. Validate that the chosen deployment has the TimescaleDB extension installed and enabled. See the [TimescaleDB documentation](https://docs.timescale.com/) and [Quarkus datasource guide](https://quarkus.io/guides/datasource).
- **openHAB supports hypertables.** Confirm the currently persisted source table's schema and hypertable status, then use the supported TimescaleDB hypertable configuration where appropriate; do not treat openHAB compatibility as a blocker.
- **Wattwerk owns transformations.** Use SQL for set-based aggregation and joins, and adopt TimescaleDB features only where they benefit the workload. Use Quarkus scheduling to trigger jobs, coordinate dependencies, report failures, and manage retries.
- **Keep data ownership and privileges clear.** Prefer a dedicated database or schema and separate database roles for openHAB and Wattwerk. Grant Wattwerk read access to source readings and write access only to its output schema/tables.
- **Use explicit units and timestamps.** Preserve source timestamps, item identity, numeric value, and unit. Document whether stored timestamps are UTC instants and how local-calendar windows are calculated.
- **Keep outputs idempotent.** Define unique keys for each output series and time bucket, and use transactional upserts or delete-and-recompute for bounded periods. For hypertables, include the time partitioning column in unique constraints and conflict targets.
- **Use Quarkus JDBC and plain SQL initially.** The calculations are SQL-centric, so use the PostgreSQL JDBC extension and Quarkus-managed datasource/connection pool; Hibernate ORM is optional and is not required to access TimescaleDB or create/query hypertables.

## Phased migration

### Phase 1: Confirm the source and contract

1. Verify the exact openHAB JDBC persistence add-on, the existing PostgreSQL table layout, update strategy, item-to-row mapping, timestamp behavior, and whether the current raw table is already a TimescaleDB hypertable. Confirm its hypertable partitioning and indexes are compatible with openHAB's writes and retention needs.
2. Confirm the existing openHAB items and their units, signs, and update frequencies for all measurements referenced by Flux tasks.
3. Inspect the existing openHAB PostgreSQL destination and determine whether it is already TimescaleDB. If it is plain PostgreSQL, plan the TimescaleDB installation/extension enablement and any required hypertable conversion without disrupting openHAB persistence. Determine whether Wattwerk should share the existing database or use a separate schema/database on that server.
4. Identify the downstream consumers and the output names, units, timestamp conventions, and retention they require.
5. Decide how much historical Influx data must be retained and backfilled. Estimate volume before selecting a bulk-export/import method.

**Deliverable:** a source-to-target mapping against the existing openHAB PostgreSQL data, confirmation of TimescaleDB/hypertable readiness, and a reviewed calculation inventory. Do not write production transformations until the real JDBC schema and units are known.

### Phase 2: Create the independent Wattwerk service

1. Create a new Quarkus repository with its own build, configuration, deployment, and documentation.
2. Add Quarkus PostgreSQL JDBC connectivity to the existing source database (including its managed datasource/connection pool), Liquibase-managed Wattwerk-owned schema, health checks, structured logs, and configuration externalized from source control. Use plain JDBC/SQL for transformation execution; do not add Hibernate ORM unless a later requirement benefits from entity persistence.
3. Ensure the existing PostgreSQL destination is a TimescaleDB deployment and the extension is enabled in the target database. If installation or conversion is needed, do it through an appropriately privileged deployment/migration step; keep extension installation privileges separate from the runtime Wattwerk role.
4. Configure a least-privilege database role. Avoid running the application as a PostgreSQL superuser.
5. Create separate `aggregated` and `history` hypertables so the outputs can have distinct retention policies; retain ordinary PostgreSQL tables for small reference/configuration data. Set and document chunk intervals and indexes based on the query and ingestion patterns.
6. Configure the five-year TimescaleDB retention policy for `aggregated`; leave `history` without an automatic retention policy. Add or alter these policies through versioned Liquibase migrations.
7. Add a scheduler with explicit job names, intervals, time-zone behavior, concurrency protection, and observable success/failure status.
8. Add tests against a disposable TimescaleDB test service, not merely generic PostgreSQL, so extension setup, hypertable DDL, indexes, retention policies, and time-series SQL are exercised. Include representative rows matching the existing openHAB JDBC schema.

**Deliverable:** a deployable service that connects to the intended TimescaleDB database and can run a harmless scheduled test job.

### Phase 3: Implement calculations in dependency order

1. Implement shared SQL/helpers for time windows, missing samples, item selection, and safe upserts.
2. Implement 15-second derived power calculations.
3. Implement 1-/5-minute smoothing and short-window aggregates.
4. Implement daily energy and hourly cost calculations.
5. Implement daily cost/revenue outputs.
6. Implement monthly and yearly rollups.
7. Track dependencies so a rerun of an upstream calculation can trigger or invalidate downstream aggregates where necessary.

Keep transformation logic in version-controlled SQL migrations or files packaged with Wattwerk. Use the scheduler to invoke those named calculations and record outcomes; avoid embedding large, opaque SQL strings in scheduling code.

**Deliverable:** all output series represented in TimescaleDB with documented units, keys, scheduling, and recalculation windows.

### Phase 4: Backfill and validate

1. Export the required historical readings from Influx separately; the resource YAML is not a data backup.
2. Use the history already persisted by openHAB in PostgreSQL/TimescaleDB. Import historical data from Influx only for any identified gaps in the existing source history or where required derived inputs are unavailable.
3. Run Wattwerk calculations over a bounded representative range, then expand the range for backfill.
4. Compare Influx and TimescaleDB outputs for the same timestamps and periods. Include:
   - 15-second and 1-/5-minute samples;
   - day, month, and year boundaries;
   - both daylight-saving transitions in `Europe/Vienna`;
   - missing and delayed observations;
   - positive and negative power values;
   - tariff boundaries and the revenue effective date.
5. Investigate every material discrepancy. Preserve existing behavior unless a formula correction is explicitly approved and documented.

**Deliverable:** a parity report with accepted tolerances and known differences.

### Phase 5: Shadow operation and cutover

1. Keep the existing openHAB PostgreSQL persistence and Influx persistence/tasks running; do not interrupt the established parallel persistence paths.
2. Run Wattwerk in shadow mode against the existing PostgreSQL/TimescaleDB readings and compare its outputs with Influx on a regular schedule.
3. After parity criteria are met, switch downstream consumers to Wattwerk's TimescaleDB outputs.
4. Keep Influx available and avoid deleting its data/tasks during an agreed rollback period.
5. Once stable, stop Influx tasks and then retire Influx writes. Keep openHAB's PostgreSQL/TimescaleDB persistence active. Verify backups, restore procedures, TimescaleDB/PostgreSQL monitoring, and extension-version compatibility before decommissioning Influx.

**Rollback:** stop or disable Wattwerk calculations and return consumers to the still-running Influx outputs while the old system remains intact.

## Operational requirements

- **Concurrency:** prevent the same output window from being processed concurrently; use database locks, job leases, or equivalent.
- **Retries and failures:** failed jobs must be visible and retried safely. Do not silently mark partial calculations as successful.
- **Late/corrected readings:** define a lookback window and recompute affected buckets. Propagate changes through daily/monthly/yearly rollups.
- **Time handling:** store instants consistently and derive local calendar boundaries with the IANA `Europe/Vienna` zone; do not assume every local day has 24 hours.
- **Observability:** log job name, source/output range, duration, rows read/written, and failure details. Provide health/readiness signals and alerts for repeated failures or stale outputs.
- **Backups:** include raw data, derived data, schema migrations, and any tariff configuration required to reproduce results. Verify that the chosen PostgreSQL backup/restore process also covers TimescaleDB hypertables and extension-dependent objects.
- **Security:** keep credentials outside the repository, restrict database network access, and use separate least-privilege credentials for openHAB and Wattwerk.

## Decisions still needed

- Exact openHAB JDBC persistence add-on and resulting PostgreSQL-compatible schema.
- Whether the existing openHAB PostgreSQL destination is already TimescaleDB; the database/schema Wattwerk will use; and how the extension is provisioned/upgraded if needed.
- Hypertable chunk intervals and indexes, and the raw openHAB data retention requirement.
- Required historical backfill range and any maintenance window needed to enable TimescaleDB or convert existing tables.
- Output contract and downstream consumers, including whether Wattwerk needs to expose an API.
- Confirmed units and meanings for all power, energy, price, cost, and revenue values.
- Whether to preserve any existing formula quirks as-is or handle corrections separately.

## Acceptance criteria

- Wattwerk reads the existing openHAB PostgreSQL/TimescaleDB raw readings; openHAB persistence remains active, and Wattwerk does not depend on Influx for ongoing operation.
- Every calculation represented by the 37 exported Flux tasks has an identified TimescaleDB equivalent or an explicitly approved retirement.
- Repeated runs produce no duplicate logical outputs and correctly update recalculated windows.
- Output parity meets agreed tolerances across normal periods, DST changes, missing data, and tariff changes; TimescaleDB-specific schema and retention behavior are tested where used.
- Job failures, stale results, and database connectivity problems are observable and recoverable.
- A tested backup and rollback path exists before Influx is decommissioned.
