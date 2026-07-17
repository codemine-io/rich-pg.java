# Audit: pgenie Java telemetry names vs. OTel semantic conventions for DB clients and connection pools

Research for [pgenie-io/java.gen-design#8](https://github.com/pgenie-io/java.gen-design/issues/8), a child of the observability wayfinder map ([#6](https://github.com/pgenie-io/java.gen-design/issues/6)).

Researched 2026-07-10 against primary sources only: the rendered spec at `opentelemetry.io/docs/specs/semconv/database/*`, the authoritative YAML model in `open-telemetry/semantic-conventions` on GitHub (fetched from `raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/*`, current `main` tip as of this writing), the OTel instrumentation-scope spec, and this repo's own `music-catalogue/` source. This is a sibling to [`docs/research/otel-instrumentation-survey.md`](./otel-instrumentation-survey.md) (which surveyed off-the-shelf instrumentation *libraries*); this doc is scoped strictly to semantic-conventions *naming* — it does not re-litigate the build-vs-adopt question.

## 1. Summary

pgenie's current telemetry in `music-catalogue` predates the stable 2024/2025 database semantic conventions and uses a mix of now-deprecated attribute names (`db.system`, `db.user` — the latter has **no replacement**, it was removed outright) alongside one already-correct name (`db.query.text`, which happens to match the current spec) and several `pgenie.*`/bare (`pool.name`) names that have no semconv equivalent at all. The span-naming scheme (statement class name as the span name, e.g. `InsertAlbum`; literal `"transaction"` for the transaction span) diverges structurally from the spec's naming template, which is stable and prescribes `{db.query.summary}` → `{db.operation.name} {target}` → `{target}` → `{db.system.name}`, not a raw class/type name. The pool metrics (`pgenie.musiccatalogue.pool.connections.*`) are structurally close to the `db.client.connection.*` family but every attribute and metric name differs, and **the entire `db.client.connection.*` family — including the required `db.client.connection.pool.name` and `db.client.connection.state` attributes — is still `stability: development`** per the model YAML, not stable. By contrast, `db.client.operation.duration` (the statement-duration metric equivalent) and the `span.db.client` span shape are both `stability: stable`. This split matters directly for adoption sequencing: the statement-span/statement-metric surface can be renamed to spec now with reasonable confidence it won't move again soon; the pool-metric surface is still a moving target upstream and renaming to it today is a bet on an unstable convention. Finally, "musiccatalogue" the artifact identity currently leaks into telemetry in *two* uncoordinated places at once — the instrumentation scope name (`io.pgenie.artifacts.myspace.musiccatalogue`) and the metric name prefix (`pgenie.musiccatalogue.*`) — which is redundant and not how the spec's own instrumentation-scope guidance recommends carrying identity.

## 2. Current state (verified against source, not the ticket text)

All file:line references are against `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/` at commit `822f84c` (branch `more-features`), cross-checked against `music-catalogue/src/test/java/io/pgenie/artifacts/myspace/musiccatalogue/TelemetryIT.java`, which asserts on several of these names directly and is treated here as a second source of truth. There is exactly one artifact module in this repo (`music-catalogue/`) and no shared/common observability module — every name below is bespoke to this one artifact, hand-written, not generated from a shared template. `music-catalogue/pom.xml:34-42` pins `com.zaxxer:HikariCP:7.0.2` and `io.opentelemetry:opentelemetry-api:1.49.0`.

### Instrumentation scope

- Scope name: `"io.pgenie.artifacts.myspace.musiccatalogue"`, version `"1.0.1"` — constants at `MusicCatalogueSession.java:48-49`, used to acquire the `Tracer`/`Meter` at `MusicCatalogueSession.java:82-83`.

### Metrics

| Name | Instrument | Unit | Attributes | Source |
|---|---|---|---|---|
| `pgenie.musiccatalogue.statement.duration` | `DoubleHistogram` | `s` | `db.query.text`, `pgenie.statement.name`, `pgenie.statement.batch_size` (batches only) | built `MusicCatalogueSession.java:89-93`; recorded `StatementExecutor.java:180-195`, attributes at `:184-189` |
| `pgenie.musiccatalogue.transaction.retries` | `LongCounter` | (none) | none | built `MusicCatalogueSession.java:85-88`; incremented `MusicCatalogueSession.java:282-285` |
| `pgenie.musiccatalogue.pool.connections.active` | `ObservableLongGauge` | (none) | `pool.name` | `MusicCatalogueSession.java:115-123` |
| `pgenie.musiccatalogue.pool.connections.idle` | `ObservableLongGauge` | (none) | `pool.name` | `MusicCatalogueSession.java:125-133` |
| `pgenie.musiccatalogue.pool.connections.pending` | `ObservableLongGauge` | (none) | `pool.name` | `MusicCatalogueSession.java:135-143` |
| `pgenie.musiccatalogue.pool.connections.total` | `ObservableLongGauge` | (none) | `pool.name` | `MusicCatalogueSession.java:145-153` |

All four pool gauges read `HikariPoolMXBean` via a callback (`MusicCatalogueSession.java:112-154`) — HikariCP's own `MetricsTrackerFactory` SPI is **not** registered anywhere in `createHikariDataSource` (`MusicCatalogueSession.java:101-110`), so there's no create/wait/use-time histogram or timeout counter today (confirmed absent from the source; matches the finding already recorded in `otel-instrumentation-survey.md:33-41`, not re-derived here).

### Spans

| Span name | Kind | Attributes | Source |
|---|---|---|---|
| statement class simple name, e.g. `InsertAlbum` | `CLIENT` | `db.system`=`"postgresql"`, `db.query.text`=SQL, `pgenie.statement.name`=class name, `db.user`=`config.user()` | `StatementExecutor.startStatementSpan`, `StatementExecutor.java:128-140`; name derivation `StatementExecutor.java:71` (`statement.getClass().getSimpleName()`) |
| `"batch"` (literal) | `CLIENT` | same as above plus `pgenie.statement.batch_size` | `StatementExecutor.startBatchSpan`, `StatementExecutor.java:142-155`; name literal `StatementExecutor.java:111` |
| `"transaction"` (literal) | `INTERNAL` | `db.system`=`"postgresql"`, `db.transaction.isolation_level`, `pgenie.transaction.max_attempts`, `pgenie.transaction.read_only` | `MusicCatalogueSession.executeTransaction`, `MusicCatalogueSession.java:268-276` |
| `"healthCheck"` (literal) | `CLIENT` | `db.system`=`"postgresql"` | `MusicCatalogueSession.java:318-323` |
| `"musiccatalogue.session.close"` (literal) | default (`INTERNAL`) | none | `MusicCatalogueSession.java:354` |

`TelemetryIT.java:59-71` independently confirms the span names `InsertAlbum`/`transaction` and the metric names `pgenie.musiccatalogue.statement.duration`, `pgenie.musiccatalogue.pool.connections.active/idle/total` are what actually gets emitted at runtime, not just what the code intends.

### Attribute key constants (verbatim)

| Constant | Key string | Type | Defined | Value source |
|---|---|---|---|---|
| `DB_SYSTEM_KEY` | `db.system` | string | `StatementExecutor.java:33`, `MusicCatalogueSession.java:53` | literal `"postgresql"` |
| `DB_QUERY_TEXT_KEY` | `db.query.text` | string | `StatementExecutor.java:34` | raw SQL text, unsanitized |
| `DB_USER_KEY` | `db.user` | string | `StatementExecutor.java:35` | `config.user()` |
| `STATEMENT_NAME_KEY` | `pgenie.statement.name` | string | `StatementExecutor.java:36` | statement class simple name |
| `BATCH_SIZE_KEY` | `pgenie.statement.batch_size` | long | `StatementExecutor.java:37` | batch size |
| `POOL_NAME_KEY` | `pool.name` | string | `MusicCatalogueSession.java:54` | literal `"music-catalogue-pool"` (`MusicCatalogueSession.java:51,108`) |
| `ISOLATION_LEVEL_KEY` | `db.transaction.isolation_level` | string | `MusicCatalogueSession.java:55` | `settings.isolationLevel().name()` |
| `MAX_ATTEMPTS_KEY` | `pgenie.transaction.max_attempts` | long | `MusicCatalogueSession.java:56` | `settings.maxAttempts()` |
| `READ_ONLY_KEY` | `pgenie.transaction.read_only` | boolean | `MusicCatalogueSession.java:57` | `settings.readOnly()` |

Note the artifact identity `"musiccatalogue"` currently appears in **two independent places at once**: the instrumentation scope name (`io.pgenie.artifacts.myspace.musiccatalogue`) and the metric-name prefix (`pgenie.musiccatalogue.*`). It does not appear in span names or in any attribute value — there is no artifact-identifying attribute on spans at all today.

## 3. OTel semantic conventions for database client calls

Primary sources: [database-spans doc](https://opentelemetry.io/docs/specs/semconv/database/database-spans/), the YAML span model at [`model/db/spans.yaml`](https://github.com/open-telemetry/semantic-conventions/blob/main/model/db/spans.yaml), the attribute registry at [`model/db/registry.yaml`](https://github.com/open-telemetry/semantic-conventions/blob/main/model/db/registry.yaml) and rendered [attributes-registry/db](https://opentelemetry.io/docs/specs/semconv/attributes-registry/db/).

### Span naming

The `span.db.client` group (`model/db/spans.yaml`) is `stability: stable`. Its note points to the rendered [Name section](https://opentelemetry.io/docs/specs/semconv/database/database-spans/) for the naming template, which resolves in priority order to:

1. `{db.query.summary}` if available (low-cardinality query summary)
2. `{db.operation.name} {target}` if a low-cardinality operation name exists
3. `{target}` alone if no operation name
4. `{db.system.name}` as final fallback

where `{target}` prefers `db.collection.name` > `db.stored_procedure.name` > `db.namespace` > `server.address:server.port`. Nothing in this template names a span after a client-side type/class name — the pgenie pattern of using the statement class simple name (`InsertAlbum`) as the span name has no counterpart in the spec; the closest legitimate value would be something like `SELECT albums` (operation + target) or a query summary, not a generated-class identifier.

Span kind guidance: `span_kind: client` is the spec default for `span.db.client`, with a note that it MAY be `INTERNAL` "on spans representing in-memory database calls" (`model/db/spans.yaml`). pgenie's statement/batch/healthCheck spans already use `CLIENT`, correctly; the `"transaction"` span uses `INTERNAL`, which is spec-compatible for a logical, non-network-boundary wrapper span, though the spec has no dedicated "transaction span" concept at all — that shape (and its `pgenie.transaction.*` attributes) is entirely a pgenie extension.

### Attribute renames — exact old→new mapping, from `model/db/deprecated/registry-deprecated.yaml`

| Deprecated (old) | Renamed to (current) | Stability of new name |
|---|---|---|
| `db.statement` | `db.query.text` | stable |
| `db.operation` | `db.operation.name` | stable |
| `db.name` | `db.namespace` | stable |
| `db.system` | `db.system.name` | stable (see caveat below) |
| `db.connection_string` | *(removed — use `server.address`+`server.port`)* | — |
| `db.user` | **no replacement** ("Removed, no replacement at this time.") | — |
| `db.client.connections.pool.name` | `db.client.connection.pool.name` | development |
| `db.client.connections.state` | `db.client.connection.state` | development |

Source: `model/db/deprecated/registry-deprecated.yaml`, `id: db.system` block (`deprecated: {reason: renamed, renamed_to: db.system.name}`) and `id: db.user` block (`deprecated: {reason: obsoleted, note: Removed, no replacement at this time.}`), and `id: db.statement`/`db.operation`/`db.name` blocks, all fetched from `raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/deprecated/registry-deprecated.yaml`.

**Caveat on `db.system.name`**: the *attribute* is `stability: stable`, but individual enum *values* have their own independent stability in `model/db/registry.yaml`. Of the values relevant here, `postgresql`, `mysql`, `mariadb`, and `microsoft.sql_server` are `stability: stable`; the great majority of other DB system identifiers (e.g. `mongodb`, `redis`, `oracle.db`, `sqlite`) are still `stability: development`. pgenie's literal value `"postgresql"` (`StatementExecutor.java:31`, `MusicCatalogueSession.java:50`) is on the stable list, so renaming the key from `db.system` to `db.system.name` carries no value-stability risk for this artifact.

### Current attribute names (from `model/db/registry.yaml`, general-database-attributes group)

| Attribute | Stability | Notes |
|---|---|---|
| `db.system.name` | stable | required on client spans |
| `db.query.text` | stable | raw/parameterized query text |
| `db.query.summary` | stable | low-cardinality summary, used in span naming |
| `db.operation.name` | stable | e.g. `SELECT`, `INSERT` |
| `db.namespace` | stable | database/schema name |
| `db.collection.name` | stable | table/container name |
| `db.stored_procedure.name` | stable | |
| `db.operation.batch.size` | stable | batch operation count |
| `db.response.status_code` | stable | |
| `db.query.parameter.<key>` | development | opt-in only, per-parameter capture |
| `db.response.returned_rows` | development | |
| `db.client.connection.pool.name` | development | see §4 |
| `db.client.connection.state` | development | see §4 |

### Metrics for database client calls

`metric.db.client.operation.duration` (`model/db/metrics.yaml`) is `stability: stable`: a `histogram`, unit `s`, advisory buckets `[0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10]`, required attribute `db.system.name`, conditionally-required `db.namespace`/`db.operation.name`/`db.response.status_code`(if error)/`error.type`(if error), recommended `db.query.summary`/`db.stored_procedure.name`, and `db.query.text` explicitly marked `requirement_level: opt_in` (i.e. the spec itself treats raw query text as an opt-in, non-default dimension on the metric — consistent with the cardinality concern already flagged in `otel-instrumentation-survey.md:23`). Note this is the direct semconv equivalent of pgenie's `pgenie.musiccatalogue.statement.duration` histogram.

### Overall database-conventions maturity note

The top of the rendered [database semconv landing page](https://opentelemetry.io/docs/specs/semconv/database/) and the spans/metrics pages all carry the same migration admonition, in substance: *"Existing database instrumentations that are using v1.24.0 of this document (or prior) SHOULD NOT change the version of the database conventions that they emit by default in their existing major version"* and direct implementers to the `OTEL_SEMCONV_STABILITY_OPT_IN` environment variable / `database/dup` dual-emission mode for phased migration. Since pgenie's telemetry is greenfield (there is no v1.24-or-prior baseline to preserve compatibility with — this is a from-scratch generator, not an existing shipped instrumentation being migrated), this migration-compatibility admonition does not constrain pgenie: it can adopt the current stable names directly without needing a dual-emission transition period.

## 4. OTel semantic conventions for database connection pools

Primary source: `metric.db.client.connection.*` group in [`model/db/metrics.yaml`](https://github.com/open-telemetry/semantic-conventions/blob/main/model/db/metrics.yaml), attributes in [`model/db/registry.yaml`](https://github.com/open-telemetry/semantic-conventions/blob/main/model/db/registry.yaml), rendered at [database-metrics](https://opentelemetry.io/docs/specs/semconv/database/database-metrics/).

| Metric | Instrument | Unit | Stability (YAML) |
|---|---|---|---|
| `db.client.connection.count` | `updowncounter` | `{connection}` | development |
| `db.client.connection.idle.max` | `updowncounter` | `{connection}` | development |
| `db.client.connection.idle.min` | `updowncounter` | `{connection}` | development |
| `db.client.connection.max` | `updowncounter` | `{connection}` | development |
| `db.client.connection.pending_requests` | `updowncounter` | `{request}` | development |
| `db.client.connection.timeouts` | `counter` | `{timeout}` | development |
| `db.client.connection.create_time` | `histogram` | `s` | development |
| `db.client.connection.wait_time` | `histogram` | `s` | development |
| `db.client.connection.use_time` | `histogram` | `s` | development |

**Every single instrument in this family is `stability: development`** — none are stable, confirmed directly from `model/db/metrics.yaml` (each block carries `stability: development` explicitly; there is no ambiguity or inference here). This is a materially different maturity status than `db.client.operation.duration` (stable, §3).

Required attributes:
- `db.client.connection.pool.name` (required on every instrument in the family) — `stability: development`, brief: *"The name of the connection pool; unique within the instrumented application. In case the connection pool implementation doesn't provide a name, instrumentation SHOULD use a combination of parameters that would make the name unique, for example, combining attributes `server.address`, `server.port`, and `db.namespace`, formatted as `server.address:server.port/db.namespace`."* (`model/db/registry.yaml`)
- `db.client.connection.state` (required on `db.client.connection.count` only) — `stability: development`, enum `idle` | `used` (both members `stability: development`) — this is how active-vs-idle is modeled: **one metric split by a state attribute**, not two separate metrics.

Legacy pre-rename names, all confirmed `stability: development` and `deprecated` in `model/db/deprecated/metrics-deprecated.yaml`: `db.client.connections.usage` → `db.client.connection.count`; `db.client.connections.idle.max/.min` → `.../idle.max/.min`; `db.client.connections.max` → `.../max`; `db.client.connections.pending_requests` → `.../pending_requests`; `db.client.connections.timeouts` → `.../timeouts`; `db.client.connections.create_time`/`wait_time`/`use_time` (unit `ms`) → their `db.client.connection.*` counterparts (unit `s` — **the unit also changed**, not just the name, per the deprecation note *"Replaced by `db.client.connection.create_time` with unit `s`"*). Legacy attribute names `state` and `pool.name` (bare, no `db.*` prefix) are also formally deprecated in favor of `db.client.connection.state`/`db.client.connection.pool.name`.

**pgenie's `pool.name` attribute key (`MusicCatalogueSession.java:54`) is literally the pre-rename legacy attribute name** `pool.name`, byte-for-byte matching the deprecated entry `id: pool.name` in `registry-deprecated.yaml` ("Deprecated, use `db.client.connection.pool.name` instead").

HikariCP's own OTel integration: this repo does not use HikariCP's `MetricsTrackerFactory` SPI at all (§2) — pool metrics are hand-rolled via JMX-bean polling, so there is no existing HikariCP-side name mismatch to reconcile; adopting `db.client.connection.*` here is purely a rename/restructure of pgenie's own gauges, not a reconciliation with an upstream HikariCP metric name (that reconciliation question is already covered for the off-the-shelf `opentelemetry-hikaricp-3.0` artifact in `otel-instrumentation-survey.md:119-136`, not repeated here).

## 5. Stability status summary

| Convention area | Stability (source) |
|---|---|
| `span.db.client` (span shape/kind/naming template) | **Stable** — `model/db/spans.yaml`, `id: span.db.client`, `stability: stable` |
| `db.system.name`, `db.query.text`, `db.query.summary`, `db.operation.name`, `db.namespace`, `db.collection.name`, `db.operation.batch.size`, `db.response.status_code` (attributes) | **Stable** — `model/db/registry.yaml` |
| `db.system.name` value `postgresql` (and `mysql`/`mariadb`/`microsoft.sql_server`) | **Stable** — `model/db/registry.yaml`, per-enum-member `stability` field |
| `db.system.name` values other than the four above (e.g. `mongodb`, `redis`, `sqlite`, `oracle.db`) | Development | `model/db/registry.yaml` |
| `db.client.operation.duration` (metric) | **Stable** — `model/db/metrics.yaml`, `id: metric.db.client.operation.duration`, `stability: stable` |
| `db.query.parameter.<key>`, `db.response.returned_rows` (attributes) | Development (opt-in only) | `model/db/registry.yaml` |
| **Entire `db.client.connection.*` metric family** (count, idle.max, idle.min, max, pending_requests, timeouts, create_time, wait_time, use_time) | **Development** — every entry in `model/db/metrics.yaml` carries `stability: development` explicitly | |
| `db.client.connection.pool.name`, `db.client.connection.state` (attributes) | **Development** — `model/db/registry.yaml` | |
| `db.user`, `db.statement`, `db.operation`, `db.name`, `db.system`, `pool.name`, `state`, `db.client.connections.*` (legacy names) | Deprecated (all `stability: development`, most `deprecated: {reason: renamed}`) | `model/db/deprecated/registry-deprecated.yaml`, `model/db/deprecated/metrics-deprecated.yaml` |

Practical read: the **statement-level span/metric surface is safe to adopt now** — its shape and its attribute names are marked stable and unlikely to move again soon. The **connection-pool metric surface is not** — adopting `db.client.connection.*` today means tracking a convention family the spec itself still labels experimental/development, with a real chance the metric names, attribute names, or even units (as already happened once, `ms`→`s`) change again before GA.

## 6. Side-by-side comparison table

| Current pgenie name/attribute (file:line) | Semconv equivalent | Semconv stability | Gap / verdict |
|---|---|---|---|
| `pgenie.musiccatalogue.statement.duration` histogram (`MusicCatalogueSession.java:89-93`) | `db.client.operation.duration` | Stable | **Renamed upstream** — pgenie's is a bespoke name for a concept the spec now standardizes; unit (`s`) already matches. |
| `pgenie.musiccatalogue.pool.connections.active`/`.idle`/`.pending`/`.total` (`MusicCatalogueSession.java:115-153`) | `db.client.connection.count` (state=`used`/`idle`), `.pending_requests`, and `.max` (no exact `total` equivalent) | Development | **No semconv equivalent name today, and target is unstable** — active/idle collapse into one metric split by `db.client.connection.state`; `total` has no 1:1 match (closest is `.max`, a config ceiling, not a live total). |
| `pgenie.musiccatalogue.transaction.retries` counter (`MusicCatalogueSession.java:85-88`) | none | — | **pgenie-specific, stays bespoke** — no OTel db semconv models "retries of a logical transaction." |
| Statement span name = statement class name, e.g. `InsertAlbum` (`StatementExecutor.java:71,128`) | span name per template `{db.query.summary}` / `{db.operation.name} {target}` / `{target}` / `{db.system.name}` | Stable (span shape); template itself has no class-name concept | **Structural mismatch** — spec never names a span after a generated class; would need `db.operation.name`+`db.collection.name` derived from the SQL, or a `db.query.summary`. |
| `"transaction"` span name, `INTERNAL` kind (`MusicCatalogueSession.java:268-276`) | no dedicated concept | — | **pgenie-specific, stays bespoke** — spec has no "wrap a retry-capable logical transaction" span type. |
| `db.system` attribute = `"postgresql"` (`StatementExecutor.java:33,132`; `MusicCatalogueSession.java:53,272,322`) | `db.system.name` | Stable (attribute); value `postgresql` also stable | **Exact rename** — trivial 1:1 key rename, value already on the stable enum list. |
| `db.query.text` attribute (`StatementExecutor.java:34,133,147`) | `db.query.text` | Stable | **Exact match already** — no change needed, though spec marks it `opt_in` on the *metric* (not the span) and recommends sanitization by default. |
| `db.user` attribute (`StatementExecutor.java:35,135,150`) | *(none — removed, no replacement)* | Deprecated/removed | **No standards path** — either drop it or keep as a pgenie extension attribute (not `db.*` namespaced, since that namespace is reserved). |
| `pool.name` attribute = `"music-catalogue-pool"` (`MusicCatalogueSession.java:54,113`) | `db.client.connection.pool.name` | Development | **Legacy pre-rename name, byte-for-byte** — literally the deprecated key `pool.name` per `registry-deprecated.yaml`; target itself still unstable. |
| `pgenie.statement.name` attribute (`StatementExecutor.java:36,134,148`) | none | — | **pgenie-specific, stays bespoke** — generated-statement-class identity has no semconv concept (already noted in `otel-instrumentation-survey.md:148`). |
| `pgenie.statement.batch_size` attribute (`StatementExecutor.java:37,149,188`) | `db.operation.batch.size` | Stable | **Roughly maps** — different name, same concept (count of operations in a batch). |
| `db.transaction.isolation_level` attribute (`MusicCatalogueSession.java:55,273`) | none found in `model/db/registry.yaml` or `common.yaml` | — | **No semconv attribute exists for transaction isolation level** — stays a pgenie/general-db extension; not reserved namespace conflict since it's plausible future semconv territory, but not modeled today. |
| `pgenie.transaction.max_attempts` / `pgenie.transaction.read_only` (`MusicCatalogueSession.java:56-57,274-275`) | none | — | **pgenie-specific, stays bespoke.** |

## 7. Proposed standards-aligned naming scheme (audit finding — not a locked decision)

This section is a recommendation to carry into the design-locking ticket, not the design itself.

### Names that should come straight from semconv

- `db.system.name` (replacing `db.system`) — value `"postgresql"` stays as-is, already on the stable enum list.
- `db.query.text` — already correct, no change.
- `db.operation.name` and `db.collection.name` — should be *added*; currently absent. These would let span naming follow the actual spec template instead of the class-name scheme.
- `db.namespace` — should be added (database name); currently absent entirely from pgenie's attribute set.
- `db.operation.batch.size` (replacing `pgenie.statement.batch_size`) — direct rename, stable target.
- `db.client.operation.duration` (replacing `pgenie.musiccatalogue.statement.duration`) — direct rename, stable target, same unit (`s`).
- Span naming: adopt the spec's template (`{db.operation.name} {target}` as the primary case for a generated single-table statement, e.g. `SELECT albums`) instead of the generated-class name, once `db.operation.name`/`db.collection.name` are derivable from the statement's SQL or from generator metadata.
- `db.client.connection.pool.name` and `db.client.connection.state` (replacing `pool.name` and the implicit active/idle split) — semantically correct target names, **but see the stability caveat below before treating this as a "should adopt now."**

### Names that need a pgenie-specific extension (no semconv equivalent exists)

- `pgenie.statement.name` — generated-statement-class identity. No DB semconv concept models "which generated code object issued this query"; this is inherently pgenie's own generator metadata, not a database concept.
- `pgenie.transaction.max_attempts`, `pgenie.transaction.read_only`, and the `"transaction"` span itself — the spec has no notion of a client-side retry-capable logical transaction wrapper; this is pgenie/vendor-retry-loop specific.
- `pgenie.musiccatalogue.transaction.retries` — same reasoning; "number of retries of a logical transaction" isn't a database concept, it's a client-side control-flow concept.
- `db.transaction.isolation_level` — kept in the `db.*` shape informally today, but it does not exist in the current registry; either petition upstream (out of scope here) or keep it as an explicitly pgenie-owned attribute under a non-`db.*` prefix to avoid squatting on the reserved namespace, e.g. `pgenie.transaction.isolation_level`.
- `healthCheck` / `musiccatalogue.session.close` spans — session-lifecycle concepts specific to the generated artifact's API surface (a hand-rolled `select 1` and pool-drain-then-close), not database-protocol concepts; no semconv target exists or is likely to.
- `db.user` — since the semconv attribute was removed with no replacement, if pgenie wants to keep recording the configured database user (useful for audit/debugging), it should move to a pgenie-namespaced key, not stay under `db.*`.

### Per-artifact parameterization recommendation

Today "musiccatalogue" is embedded in two places simultaneously: the instrumentation scope name (`io.pgenie.artifacts.myspace.musiccatalogue`) and the metric name prefix (`pgenie.musiccatalogue.*`). The [instrumentation-scope spec](https://opentelemetry.io/docs/specs/otel/common/instrumentation-scope/) frames scope name as identifying "the logical unit of software that emits telemetry" (i.e., the instrumentation/library itself), with the recommendation to "use the name and version of the instrumentation library, with any additional identifying information as part of the scope's attributes" — scope name for *what code is emitting this*, scope/resource *attributes* for *which specific instance/target it's about*.

Recommendation: **keep the artifact identity in the instrumentation scope name only, and drop it from metric names.** Concretely:
- Instrumentation scope stays fully-qualified per generated artifact package (as it already is: `io.pgenie.artifacts.<group>.<artifact>`) — this is legitimate because each generated artifact genuinely is a distinct emitting library/module, matching the spec's "fully qualified name of the emitting software unit" guidance.
- Metric and span names should be the bare semconv names (`db.client.operation.duration`, not `pgenie.musiccatalogue.statement.duration`) — universal across every pgenie-generated artifact, so dashboards/alerts built against `db.client.operation.duration` work unmodified regardless of which artifact emitted it.
- The specific catalogue/database instance identity (what today's `pool.name = "music-catalogue-pool"` and the `musiccatalogue` metric-name segment both encode) belongs in attribute *values*, not names: `db.namespace` (the actual database/schema name) and `db.client.connection.pool.name` are the semconv-correct carriers for that. If a pgenie-specific "which generated artifact" tag is still wanted on top of that (e.g. for cases where scope name alone isn't queryable in a given backend), it should be a resource or span/metric attribute value (e.g. a pgenie-namespaced `pgenie.artifact.name` = `"musiccatalogue"`), not a name-prefix segment — this keeps names universal and pushes all identity into dimensions, consistent with how the spec itself separates "source of telemetry" (scope) from "target being observed" (attributes/resource).

Reasoning for not baking the artifact name into the metric name: OTel semconv metric names are designed to be joined/aggregated across services and instrumentation sources in a single backend (that's the entire point of standardizing them); a `pgenie.musiccatalogue.*`-style prefix defeats that by making every generated artifact emit differently-named metrics for the identical concept, forcing per-artifact dashboard/alert duplication that a shared `db.client.operation.duration` name with a distinguishing attribute would avoid.

Given §5's stability split, the sequencing implication (flagged here, to be finalized in the design-locking ticket) is: rename the stable surface (statement span/metric, `db.system.name`, `db.query.text`, batch size) now; treat the `db.client.connection.*` pool-metric rename as tracked-but-deferred, or adopt it provisionally behind the same kind of opt-in/dual-emission pattern the spec itself recommends for its own in-flux conventions, rather than treating it as settled.

## 8. Sources consulted

- [`docs/research/otel-instrumentation-survey.md`](./otel-instrumentation-survey.md) — sibling doc, read for format/tone/baseline-inventory cross-check, not duplicated here.
- `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/MusicCatalogueSession.java` — current metric/span/attribute source (§2).
- `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/StatementExecutor.java` — current statement/batch span+metric source (§2).
- `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/ObservableTransactionContext.java` — commit/rollback bookkeeping backing the retry counter (§2).
- `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/MusicCatalogueConfig.java` — confirms `OpenTelemetry` wiring, no bespoke `Tracer`/`Meter` construction here.
- `music-catalogue/src/test/java/io/pgenie/artifacts/myspace/musiccatalogue/TelemetryIT.java` — runtime confirmation of emitted span/metric names.
- `music-catalogue/pom.xml` — HikariCP 7.0.2 / OTel API 1.49.0 version pins.
- [opentelemetry.io/docs/specs/semconv/database/](https://opentelemetry.io/docs/specs/semconv/database/) — landing page, overall section structure and migration admonition.
- [opentelemetry.io/docs/specs/semconv/database/database-spans/](https://opentelemetry.io/docs/specs/semconv/database/database-spans/) — span naming template, required/recommended/opt-in attributes.
- [opentelemetry.io/docs/specs/semconv/database/database-metrics/](https://opentelemetry.io/docs/specs/semconv/database/database-metrics/) — `db.client.operation.duration` and `db.client.connection.*` rendered docs.
- [opentelemetry.io/docs/specs/semconv/attributes-registry/db/](https://opentelemetry.io/docs/specs/semconv/attributes-registry/db/) — rendered attribute registry, cross-checked against the YAML.
- [opentelemetry.io/docs/specs/otel/common/instrumentation-scope/](https://opentelemetry.io/docs/specs/otel/common/instrumentation-scope/) — scope-naming guidance backing §7's parameterization recommendation.
- `github.com/open-telemetry/semantic-conventions`, `model/db/spans.yaml` ([raw](https://raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/spans.yaml)) — authoritative span-group YAML, `stability: stable` on `span.db.client`.
- `github.com/open-telemetry/semantic-conventions`, `model/db/metrics.yaml` ([raw](https://raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/metrics.yaml)) — authoritative metric definitions and per-metric `stability` fields (this is where the "entire `db.client.connection.*` family is development" finding comes from directly, not inferred).
- `github.com/open-telemetry/semantic-conventions`, `model/db/registry.yaml` ([raw](https://raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/registry.yaml)) — authoritative attribute registry including per-enum-member stability for `db.system.name` values.
- `github.com/open-telemetry/semantic-conventions`, `model/db/deprecated/registry-deprecated.yaml` ([raw](https://raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/deprecated/registry-deprecated.yaml)) — exact old→new attribute renames, including the `db.user` "no replacement" case.
- `github.com/open-telemetry/semantic-conventions`, `model/db/deprecated/metrics-deprecated.yaml` ([raw](https://raw.githubusercontent.com/open-telemetry/semantic-conventions/main/model/db/deprecated/metrics-deprecated.yaml)) — exact old→new metric renames for the connection-pool family, including the `ms`→`s` unit change on the time histograms.
