# WattWerk workspace instructions

## Database ownership and safety

- The `public.items` and `public.item_meta` tables contain openHAB-owned raw readings and metadata. Treat both as strictly read-only. The `openhab.items` and `openhab.item_meta` views are read-only aliases to those tables.
- Application code may query openHAB data using plain SQL/JDBC through the `openhab` views. Never insert, update, delete, truncate, or alter openHAB data, the underlying tables, or their schema, indexes, hypertable configuration, or retention.
- Write calculated WattWerk outputs only to the `wattwerk` schema. Keep database roles least-privileged so source reads and WattWerk output writes are separated.
- The database's three time-series hypertables are `public.items`, `wattwerk.aggregated`, and `wattwerk.history`. The `openhab` schema contains views, not hypertables; keep the openHAB hypertable untouched. `wattwerk.aggregated` retains data for five years; `wattwerk.history` has no automatic expiry.
- Make output calculations idempotent and transactional. Include the time partition column in unique constraints for hypertables.

## Job documentation

- Document each scheduled job in `README.md`, including its schedule, source tables and series, calculations, output table/series, units, and time-window behavior.
- Keep the README focused on current application behavior; do not describe project migration history or refer to retired systems.
- Keep executable SQL in version-controlled SQL resources, not Java strings. Bind configurable series/item names and units as prepared-statement parameters; never interpolate configuration into SQL text.
- PostgreSQL application sessions use `Europe/Vienna` as their timezone. Prefer this configured session default for local calendar windows rather than repeating explicit timezone expressions in job SQL.
