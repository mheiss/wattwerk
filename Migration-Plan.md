# Wattwerk: InfluxDB-to-PostgreSQL Migration Plan

## Project

**Name:** Wattwerk  
**Description:** Quarkus-based home energy analytics for openHAB and PostgreSQL. Reads openHAB-persisted household energy data, calculates power, energy, cost, and revenue metrics, and stores aggregates for downstream use.

## Objective and scope

Replace the InfluxDB v2 task-processing layer with a new, standalone Quarkus service named Wattwerk. The raw-data ingestion and persistence pipeline stays in openHAB: openHAB writes readings directly to PostgreSQL using its JDBC persistence capability. Wattwerk reads those persisted readings, computes the derived series and rollups currently produced by InfluxDB tasks, and stores results in PostgreSQL.

Wattwerk is a separate project and deployment. It is not part of, or a module in, the existing dashboard backend. The dashboard may consume Wattwerk's stored results or a future API, but designing or implementing that integration is outside this migration plan unless separately requested.

## Current system, based on the Influx export

The exported resource file contains four bucket definitions and 37 Flux tasks. It includes task and bucket configuration, not the underlying historical time-series data or task execution history.

| Influx bucket | Current retention | Observed role |
| --- | --- | --- |
| `openhab` | 90 days | Raw openHAB readings and short-lived derived power/price series |
| `aggregated` | 720 days | Short-term aggregated power and cost outputs |
| `history` | Infinite | Daily, monthly, and yearly energy, cost, and revenue series |
| `evcc` | 90 days | EVCC data; no energy task in this export reads it |

Retention values are current-state observations, not a requirement that PostgreSQL use the same retention. Confirm desired raw and derived-data retention before implementation.

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

Most calendar calculations run at minute 59 of each hour; some run hourly with a ten-minute offset. The frequent jobs revisit recent ranges, and several rollups recalculate the current day, month, or year. The PostgreSQL implementation must therefore support safe repeat execution and correction of prior results, rather than append-only duplicate writes.

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
  └─ JDBC persistence ──> PostgreSQL raw readings
                              ├─> Wattwerk scheduled calculations
                              └─> Wattwerk derived/aggregate tables
                                      └─> downstream consumers (out of scope here)
```

- **openHAB owns ingestion and raw persistence.** Do not add a second ingestion path in Wattwerk unless a verified limitation of the JDBC persistence add-on requires it.
- **Wattwerk owns transformations.** Use PostgreSQL SQL for set-based aggregation and joins. Use Quarkus scheduling to trigger jobs, coordinate dependencies, report failures, and manage retries.
- **Keep data ownership and privileges clear.** Prefer a dedicated database or schema and separate database roles for openHAB and Wattwerk. Grant Wattwerk read access to source readings and write access only to its output schema/tables.
- **Use explicit units and timestamps.** Preserve source timestamps, item identity, numeric value, and unit. Document whether stored timestamps are UTC instants and how local-calendar windows are calculated.
- **Keep outputs idempotent.** Define unique keys for each output series and time bucket, and use transactional upserts or delete-and-recompute for bounded periods.
- **Start with persisted tables and SQL.** Do not introduce TimescaleDB, another database, Kafka, or a separate script-based scheduler unless measured load or a concrete requirement justifies it.

## Phased migration

### Phase 1: Confirm the source and contract

1. Verify the exact openHAB JDBC persistence add-on, its PostgreSQL table layout, update strategy, item-to-row mapping, and timestamp behavior.
2. Confirm the existing openHAB items and their units, signs, and update frequencies for all measurements referenced by Flux tasks.
3. Determine whether openHAB's destination PostgreSQL instance/database can be shared safely or whether Wattwerk should use a separate database/schema on that server.
4. Identify the downstream consumers and the output names, units, timestamp conventions, and retention they require.
5. Decide how much historical Influx data must be retained and backfilled. Estimate volume before selecting a bulk-export/import method.

**Deliverable:** a source-to-target mapping and a reviewed calculation inventory. Do not write production transformations until the real JDBC schema and units are known.

### Phase 2: Create the independent Wattwerk service

1. Create a new Quarkus repository with its own build, configuration, deployment, and documentation.
2. Add PostgreSQL connectivity, Liquibase-managed Wattwerk-owned schema, health checks, structured logs, and configuration externalized from source control.
3. Configure a least-privilege database role. Avoid running the application as a PostgreSQL superuser.
4. Add a scheduler with explicit job names, intervals, time-zone behavior, concurrency protection, and observable success/failure status.
5. Add tests against a disposable PostgreSQL-compatible test database or PostgreSQL test service. Include representative openHAB JDBC source rows.

**Deliverable:** a deployable service that connects to the intended database and can run a harmless scheduled test job.

### Phase 3: Implement calculations in dependency order

1. Implement shared SQL/helpers for time windows, missing samples, item selection, and safe upserts.
2. Implement 15-second derived power calculations.
3. Implement 1-/5-minute smoothing and short-window aggregates.
4. Implement daily energy and hourly cost calculations.
5. Implement daily cost/revenue outputs.
6. Implement monthly and yearly rollups.
7. Track dependencies so a rerun of an upstream calculation can trigger or invalidate downstream aggregates where necessary.

Keep transformation logic in version-controlled SQL migrations or files packaged with Wattwerk. Use the scheduler to invoke those named calculations and record outcomes; avoid embedding large, opaque SQL strings in scheduling code.

**Deliverable:** all output series represented in PostgreSQL with documented units, keys, scheduling, and recalculation windows.

### Phase 4: Backfill and validate

1. Export the required historical readings from Influx separately; the resource YAML is not a data backup.
2. Import history into the new raw source tables only if the openHAB-to-PostgreSQL source does not already provide the required history.
3. Run Wattwerk calculations over a bounded representative range, then expand the range for backfill.
4. Compare Influx and PostgreSQL outputs for the same timestamps and periods. Include:
   - 15-second and 1-/5-minute samples;
   - day, month, and year boundaries;
   - both daylight-saving transitions in `Europe/Vienna`;
   - missing and delayed observations;
   - positive and negative power values;
   - tariff boundaries and the revenue effective date.
5. Investigate every material discrepancy. Preserve existing behavior unless a formula correction is explicitly approved and documented.

**Deliverable:** a parity report with accepted tolerances and known differences.

### Phase 5: Shadow operation and cutover

1. Keep Influx persistence and tasks running while openHAB begins writing raw data to PostgreSQL.
2. Run Wattwerk in shadow mode and compare its outputs with Influx on a regular schedule.
3. After parity criteria are met, switch downstream consumers to Wattwerk's PostgreSQL outputs.
4. Keep Influx available and avoid deleting its data/tasks during an agreed rollback period.
5. Once stable, stop Influx tasks and then retire Influx writes. Verify backups, restore procedures, and PostgreSQL monitoring before decommissioning Influx.

**Rollback:** stop or disable Wattwerk calculations and return consumers to the still-running Influx outputs while the old system remains intact.

## Operational requirements

- **Concurrency:** prevent the same output window from being processed concurrently; use database locks, job leases, or equivalent.
- **Retries and failures:** failed jobs must be visible and retried safely. Do not silently mark partial calculations as successful.
- **Late/corrected readings:** define a lookback window and recompute affected buckets. Propagate changes through daily/monthly/yearly rollups.
- **Time handling:** store instants consistently and derive local calendar boundaries with the IANA `Europe/Vienna` zone; do not assume every local day has 24 hours.
- **Observability:** log job name, source/output range, duration, rows read/written, and failure details. Provide health/readiness signals and alerts for repeated failures or stale outputs.
- **Backups:** include raw data, derived data, schema migrations, and any tariff configuration required to reproduce results.
- **Security:** keep credentials outside the repository, restrict database network access, and use separate least-privilege credentials for openHAB and Wattwerk.

## Decisions still needed

- Exact openHAB JDBC persistence add-on and resulting PostgreSQL schema.
- Which PostgreSQL server/database/schema receives openHAB data and Wattwerk outputs.
- Raw and derived-data retention requirements.
- Required historical backfill range and acceptable downtime/dual-write period.
- Output contract and downstream consumers, including whether Wattwerk needs to expose an API.
- Confirmed units and meanings for all power, energy, price, cost, and revenue values.
- Whether to preserve any existing formula quirks as-is or handle corrections separately.

## Acceptance criteria

- openHAB writes raw readings directly to PostgreSQL; Wattwerk does not depend on Influx for ongoing operation.
- Every calculation represented by the 37 exported Flux tasks has an identified PostgreSQL equivalent or an explicitly approved retirement.
- Repeated runs produce no duplicate logical outputs and correctly update recalculated windows.
- Output parity meets agreed tolerances across normal periods, DST changes, missing data, and tariff changes.
- Job failures, stale results, and database connectivity problems are observable and recoverable.
- A tested backup and rollback path exists before Influx is decommissioned.
