# Survey: off-the-shelf OpenTelemetry instrumentation vs. music-catalogue's bespoke instrumentation

Research for [pgenie-io/java.gen-design#7](https://github.com/pgenie-io/java.gen-design/issues/7), a child of the observability wayfinder map (#6).

Researched 2026-07-10 against primary sources: the `open-telemetry/opentelemetry-java-instrumentation` GitHub repo (source at commit tracked by branch `main`, and the `v2.29.0` release / `2.29.0-alpha` artifacts, the latest as of this writing), Maven Central metadata, and `opentelemetry.io`'s published semantic-conventions docs.

## 1. Summary

Off-the-shelf coverage overlaps the bespoke instrumentation's *shape* closely but not its *content* or its *default behavior*. `opentelemetry-jdbc` reproduces the statement-span idea (CLIENT-kind spans per statement) and, if you opt in to two separate experimental switches, can also emit a `db.client.operation.duration` histogram and COMMIT/ROLLBACK transaction spans — but out of the box (no opt-ins) it emits **spans only, no metrics**, using legacy/non-standard attribute names (`db.statement`, `db.name`, `pool.name`, `state`) rather than the stable 2024/2025 semantic conventions (`db.query.text`, `db.namespace`, `db.client.connection.pool.name`, `db.client.connection.state`) unless you also pass `-Dotel.semconv-stability.opt-in=database`. The clear **win** is HikariCP pool metrics: `opentelemetry-hikaricp-3.0` is a first-party, upstream implementation of HikariCP's own `MetricsTrackerFactory` SPI (no micrometer bridge, no hand-rolling needed) and it emits a strictly richer set of pool metrics than music-catalogue's four hand-rolled gauges — it adds create/wait/use-time histograms and a timeouts counter that music-catalogue does not have today. The clear **gap** is that neither artifact reproduces `pgenie.statement.name` (the generated-statement-class attribute) or the transaction-retry counter derived from commit/rollback bookkeeping — those are pgenie-specific concepts with no semantic-convention equivalent, so bespoke code stays necessary for them regardless of what's adopted off the shelf. Both off-the-shelf artifacts are published **only as `-alpha`** (every release since inception, including the current `2.29.0-alpha`) and live under the `opentelemetry-instrumentation-api-incubator` API surface, so adopting them means depending on code OTel explicitly does not consider API-stable.

## 2. Current bespoke emission (baseline inventory)

All file:line references are against `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/` at commit `822f84c` (branch `more-features`).

### Statement execution — `StatementExecutor.java`

- **Span** per single statement, started in `startStatementSpan` (`StatementExecutor.java:128-140`) and per batch in `startBatchSpan` (`StatementExecutor.java:142-155`):
  - Kind: `CLIENT` (`SpanKind.CLIENT`, `StatementExecutor.java:131`, `:145`)
  - Span name: the statement's simple class name, e.g. `InsertArtist` (`StatementExecutor.java:71`, `:111`)
  - Attributes: `db.system` = `"postgresql"` (`StatementExecutor.java:33,132`), `db.query.text` = raw SQL (`StatementExecutor.java:34,133`), `db.user` = configured user (`StatementExecutor.java:35,135`), `pgenie.statement.name` = statement class simple name (`StatementExecutor.java:36,134`), and for batches `pgenie.statement.batch_size` (`StatementExecutor.java:37,149`)
  - Parented to the supplied `parentSpan` when non-null (used for statements run inside a transaction), otherwise a root CLIENT span (`StatementExecutor.java:136-138`)
  - Status set to `OK` or `ERROR` with exception recorded (`StatementExecutor.java:77-82`, `116-121`)
- **Metric**: `pgenie.musiccatalogue.statement.duration`, a `DoubleHistogram` in seconds, built in `MusicCatalogueSession.java:89-93`, recorded per statement/batch in `StatementExecutor.recordDuration` (`StatementExecutor.java:180-195`) with attributes `db.query.text`, `pgenie.statement.name`, and (for batches) `pgenie.statement.batch_size` (`StatementExecutor.java:184-189`). Notably the histogram attaches raw SQL text and statement name as metric attributes/dimensions — high cardinality by construction.
- Slow-query threshold logging (SLF4J `warn`), not OTel (`StatementExecutor.java:192-194`) — out of scope for this comparison but worth flagging as another "signal" that off-the-shelf tooling won't replace.

### Transactions — `ObservableTransactionContext.java` + `MusicCatalogueSession.java`

- **Span** `"transaction"`, kind `INTERNAL`, started in `MusicCatalogueSession.executeTransaction` (`MusicCatalogueSession.java:268-276`):
  - Attributes: `db.system` = `"postgresql"`, `db.transaction.isolation_level` (`MusicCatalogueSession.java:55,273`), `pgenie.transaction.max_attempts` (`MusicCatalogueSession.java:56,274`), `pgenie.transaction.read_only` (`MusicCatalogueSession.java:57,275`)
  - Statement spans executed inside the transaction are parented to this span via `ObservableTransactionContext` delegating to the shared `StatementExecutor` (`ObservableTransactionContext.java:42-50`)
- **Metric**: `pgenie.musiccatalogue.transaction.retries`, a `LongCounter`, built `MusicCatalogueSession.java:85-88`, incremented once per `executeTransaction` call by a derived retry count (`MusicCatalogueSession.java:282-285`). The retry count is *inferred*, not directly observed: `ObservableTransactionContext` counts `commit()`/`rollback()` calls (`ObservableTransactionContext.java:98-107`) and `MusicCatalogueSession.retryCount` (`MusicCatalogueSession.java:308-310`) turns that into "rollbackCount if committed, else rollbackCount-1" — a pgenie-specific accounting rule tied to the vendor retry loop in `Transaction.execute`, with no off-the-shelf equivalent.

### Pool metrics — `MusicCatalogueSession.registerPoolGauges` (`MusicCatalogueSession.java:112-154`)

Four `ObservableLongGauge`s, each reading `HikariPoolMXBean` via a callback, tagged with a single `pool.name` attribute (`MusicCatalogueSession.java:113`, POOL_NAME = `"music-catalogue-pool"`, `:51`):
- `pgenie.musiccatalogue.pool.connections.active` ← `getActiveConnections()` (`:115-123`)
- `pgenie.musiccatalogue.pool.connections.idle` ← `getIdleConnections()` (`:125-133`)
- `pgenie.musiccatalogue.pool.connections.pending` ← `getThreadsAwaitingConnection()` (`:135-143`)
- `pgenie.musiccatalogue.pool.connections.total` ← `getTotalConnections()` (`:145-153`)

No create-time/wait-time/use-time histograms, no timeout counter — these are read via a JMX-style polling callback on `HikariPoolMXBean`, not via HikariCP's `MetricsTrackerFactory` SPI.

### Wiring — `MusicCatalogueConfig.java` + `MusicCatalogueSession`

- `MusicCatalogueConfig` is a record carrying `OpenTelemetry openTelemetry` (`MusicCatalogueConfig.java:35`), defaulting to `GlobalOpenTelemetry.get()` (`MusicCatalogueConfig.java:90`); it does not itself construct a `Tracer`/`Meter`.
- `MusicCatalogueSession`'s constructor pulls `Tracer`/`Meter` from `config.openTelemetry()` using instrumentation scope `"io.pgenie.artifacts.myspace.musiccatalogue"` version `"1.0.1"` (`MusicCatalogueSession.java:48-49, 82-83`), then builds the counter/histogram instruments and hands them to `StatementExecutor`.
- HikariCP: `HikariDataSource` built directly from `HikariConfig` in `createHikariDataSource` (`MusicCatalogueSession.java:101-110`) — no `MetricsTrackerFactory` is registered; pool metrics come solely from the JMX-bean-polling gauges above. `music-catalogue/pom.xml` pins `com.zaxxer:HikariCP:7.0.2` and `io.opentelemetry:opentelemetry-api:1.49.0` / `opentelemetry-sdk:1.49.0`.
- Extra spans not covered by the off-the-shelf artifacts at all: `healthCheck` (CLIENT span around a `select 1`, `MusicCatalogueSession.java:318-340`) and `musiccatalogue.session.close` (`MusicCatalogueSession.java:354-382`) — session-lifecycle spans with no OTel-instrumentation-library equivalent since they're pgenie/session-API concepts, not JDBC/pool concepts.

## 3. `opentelemetry-jdbc` (library instrumentation)

Source: `open-telemetry/opentelemetry-java-instrumentation`, module `instrumentation/jdbc/library`. All source links point at the `main` branch as of 2026-07-10; the published artifact used for the dependency/version facts below is `2.29.0-alpha` (see §3.4).

### 3.1 Spans

Three separate `Instrumenter`s are built by [`JdbcInstrumenterFactory`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jdbc/library/src/main/java/io/opentelemetry/instrumentation/jdbc/internal/JdbcInstrumenterFactory.java):

- **Statement instrumenter** (`createStatementInstrumenter`, lines ~48-90): span kind forced to `CLIENT` via `SpanKindExtractor.alwaysClient()`; span name from `DbClientSpanNameExtractor.create(getter)` (the standard semconv naming scheme: `db.query.summary` if present, else `{db.operation.name} {target}` / `{target}` / `{db.system.name}` — per the [database-spans semconv](https://opentelemetry.io/docs/specs/semconv/database/database-spans/)); attributes via `SqlClientAttributesExtractor` (sanitizes the query by default, can capture raw parameters if `otel.instrumentation.jdbc.experimental.capture-query-parameters=true`, disabling sanitization when it does).
- **DataSource instrumenter** (`createDataSourceInstrumenter`, lines ~92-100): wraps `DataSource.getConnection()`; span kind is the instrumenter default (**not** forced to CLIENT — no `SpanKindExtractor.alwaysClient()` call, so it defaults to `INTERNAL`); attributes are `CodeAttributesExtractor` (code.* — caller location) plus a small `DataSourceDbAttributesExtractor`; **span-only, no metrics attached**. This is a span the bespoke code has no equivalent of: a per-`getConnection()`-call span.
- **Transaction instrumenter** (`createTransactionInstrumenter`, lines ~102-134): produces spans for JDBC `commit()`/`rollback()` calls, kind `CLIENT`. **Disabled by default** — gated behind `otel.instrumentation.jdbc.experimental.transaction.enabled` (default `false`), documented as experimental in the [module README](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jdbc/README.md).

Usage is via `JdbcTelemetry.create(openTelemetry).wrap(dataSource)`, documented in the [library README](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jdbc/library/README.md), which wraps a `DataSource` (works well with HikariCP) — matching the wrapping approach named in the research question.

### 3.2 Attributes / semconv alignment

Attribute names are **not fixed** — they toggle between a legacy set and the stable-semconv set at JVM startup based on `SemconvStability.emitStableDatabaseSemconv()`, which is driven by the system property `otel.semconv-stability.opt-in=database` (seen wired into the module's own test task, `jvmArgs("-Dotel.semconv-stability.opt-in=database")`, in [`instrumentation/jdbc/library/build.gradle.kts`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jdbc/library/build.gradle.kts)). This means:
- **Without the opt-in** (the default): legacy attribute names such as `db.system`, `db.statement`, `db.name`, `db.connection_string` (i.e., roughly what music-catalogue's own hand-rolled `db.system`/`db.query.text`/`db.user` attributes already resemble, but not identical).
- **With the opt-in**: the stable [database-spans semconv](https://opentelemetry.io/docs/specs/semconv/database/database-spans/) names — `db.system.name` (required), `db.query.text` (recommended, sanitized), `db.query.summary`, `db.namespace`, `db.operation.name`, `db.collection.name`, `server.address`/`server.port`, `db.response.status_code` on error. The spec's own status for these span conventions is marked **Stable**, though it notes individual `db.system.name` values (well-known DB identifiers) can still be Development.

So going off-the-shelf **and** stable-aligned requires the opt-in flag; taking the default gets you spans with non-standard, soon-to-be-legacy attribute names.

### 3.3 Metrics

Both the statement and transaction instrumenters call `.addOperationMetrics(DbClientMetrics.get())`. Critically, [`DbClientMetrics.get()`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation-api-incubator/src/main/java/io/opentelemetry/instrumentation/api/incubator/semconv/db/DbClientMetrics.java) is:

```java
public static OperationMetrics get() {
  if (emitStableDatabaseSemconv()) {
    return OperationMetricsUtil.create("database client", DbClientMetrics::new);
  }
  return meter -> OperationMetricsUtil.NOOP_OPERATION_LISTENER;
}
```

i.e. **the `db.client.operation.duration` histogram is a no-op unless the same `otel.semconv-stability.opt-in=database` flag is set.** Out of the box, `opentelemetry-jdbc` is traces-only — no metrics at all. This is a meaningful gap versus music-catalogue's existing `pgenie.musiccatalogue.statement.duration` histogram, which is always emitted. When the opt-in is set, the histogram is `db.client.operation.duration` (unit `s`, recommended buckets `[0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10]`), matching the [database-metrics semconv](https://opentelemetry.io/docs/specs/semconv/database/database-metrics/), which marks this specific metric **Stable**.

### 3.4 Dependency footprint

Fetched the published POM for `io.opentelemetry.instrumentation:opentelemetry-jdbc:2.16.0-alpha` from Maven Central (`https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-jdbc/2.16.0-alpha/opentelemetry-jdbc-2.16.0-alpha.pom`; the dependency set is stable across recent versions). Compile-scope dependencies:
- `io.opentelemetry.instrumentation:opentelemetry-instrumentation-api` (stable, non-alpha)
- `io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-incubator` (same `-alpha` version as the artifact itself)
- `io.opentelemetry:opentelemetry-api`

That's it — **no transitive third-party libraries**, just first-party OTel jars. It does not pull in HikariCP or any JDBC driver. Footprint is genuinely light; the cost is entirely in the incubator-API dependency (see §3.5), not jar weight.

### 3.5 Stability

- Every published version of `opentelemetry-jdbc` on Maven Central carries the `-alpha` suffix, from the earliest entries through the current latest, `2.29.0-alpha` (confirmed via `https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-jdbc/maven-metadata.xml`, and cross-checked against the `open-telemetry/opentelemetry-java-instrumentation` GitHub release `v2.29.0`, published 2026-06-19). There is no GA/stable release track for this artifact.
- The classes doing the actual attribute/metric work (`SqlClientAttributesExtractor`, `DbClientMetrics`, `DbClientSpanNameExtractor`, `DbConnectionPoolMetrics`) live in the module `instrumentation-api-incubator`, under package `io.opentelemetry.instrumentation.api.incubator.semconv.db`. `JdbcInstrumenterFactory` itself is annotated `This class is internal and is hence not for public use. Its APIs are unstable and can change at any time.`
- Two of the three most interesting behaviors (stable attribute names, metrics emission, and transaction spans) are **off by default** and gated behind experimental system properties (`otel.semconv-stability.opt-in=database`, `otel.instrumentation.jdbc.experimental.transaction.enabled`). Query-parameter capture is behind a third flag, `otel.instrumentation.jdbc.experimental.capture-query-parameters`.

## 4. HikariCP instrumentation

### 4.1 Which route is real

The upstream `open-telemetry/opentelemetry-java-instrumentation` repo ships `instrumentation/hikaricp-3.0/library`, whose entry point [`HikariTelemetry`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/hikaricp-3.0/library/src/main/java/io/opentelemetry/instrumentation/hikaricp/v3_0/HikariTelemetry.java) is a **direct, first-party implementation of HikariCP's own `com.zaxxer.hikari.metrics.MetricsTrackerFactory` SPI** — not a micrometer-to-OTel bridge:

```java
public MetricsTrackerFactory createMetricsTrackerFactory() {
  return createMetricsTrackerFactory(null);
}
public MetricsTrackerFactory createMetricsTrackerFactory(@Nullable MetricsTrackerFactory delegate) {
  return new OpenTelemetryMetricsTrackerFactory(openTelemetry, delegate);
}
```

Usage: `hikariConfig.setMetricsTrackerFactory(HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory())` — a one-line change to `MusicCatalogueSession.createHikariDataSource` (`MusicCatalogueSession.java:101-110`) if adopted. It optionally wraps/delegates to an existing user-supplied `MetricsTrackerFactory` so it composes rather than replaces custom tracking. **This directly answers the research question's second bullet: there is a first-party OTel `MetricsTrackerFactory`; no hand-rolling and no micrometer bridge are needed.**

### 4.2 Metrics emitted

[`OpenTelemetryMetricsTrackerFactory.create(...)`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/hikaricp-3.0/library/src/main/java/io/opentelemetry/instrumentation/hikaricp/v3_0/OpenTelemetryMetricsTrackerFactory.java) builds a [`DbConnectionPoolMetrics`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation-api-incubator/src/main/java/io/opentelemetry/instrumentation/api/incubator/semconv/db/DbConnectionPoolMetrics.java) helper (same helper class the JDBC/other pool instrumentations share) and wires a `BatchCallback` plus an `IMetricsTracker` implementation. Emitted instruments, with stable-semconv names (legacy names in parens, selected by the same `otel.semconv-stability.opt-in=database` flag as §3.2):

| Instrument | Type | Unit | Stable name | Legacy name |
|---|---|---|---|---|
| Active/idle connections, split by a `db.client.connection.state`=`used`/`idle` attribute | UpDownCounter (gauge-like) | `{connection}` | `db.client.connection.count` | `db.client.connections.usage` |
| Min idle connections allowed | UpDownCounter | `{connection}` | `db.client.connection.idle.min` | `db.client.connections.idle.min` |
| Max connections allowed | UpDownCounter | `{connection}` | `db.client.connection.max` | `db.client.connections.max` |
| Pending requests waiting for a connection | UpDownCounter | `{request}` | `db.client.connection.pending_requests` | `db.client.connections.pending_requests` |
| Connection creation time | DoubleHistogram | `s`(stable)/`ms`(legacy) | `db.client.connection.create_time` | `db.client.connections.create_time` |
| Time to acquire a connection from the pool | DoubleHistogram | `s`/`ms` | `db.client.connection.wait_time` | `db.client.connections.wait_time` |
| Time a connection is checked out | DoubleHistogram | `s`/`ms` | `db.client.connection.use_time` | `db.client.connections.use_time` |
| Connection-acquisition timeouts | LongCounter | `{timeout}` | `db.client.connection.timeouts` | `db.client.connections.timeouts` |

All instruments carry a pool-name attribute (`db.client.connection.pool.name` stable / `pool.name` legacy). Note there is **no `maxIdleConnections()` call wired up in the HikariCP factory** even though `DbConnectionPoolMetrics` exposes one (`maxIdleConnections()` exists in the helper but `OpenTelemetryMetricsTrackerFactory` only calls `minIdleConnections()`, `maxConnections()`, `connections()`, `pendingRequestsForConnection()` — confirmed by reading `OpenTelemetryMetricsTrackerFactory.java` directly, no `idle.max` instrument is built for HikariCP). Unlike the JDBC statement/transaction metrics, **these pool metrics are always emitted** — `DbConnectionPoolMetrics` is not gated behind an operation-metrics no-op the way `DbClientMetrics` is; only the *attribute/metric names* switch between legacy and stable.

This is strictly a superset of music-catalogue's four hand-rolled gauges (active, idle, pending, total ≈ max): it adds create/wait/use-time histograms and a timeout counter that music-catalogue does not currently have. music-catalogue's `total` gauge (`getTotalConnections()`) has no exact match — closest is the stable `db.client.connection.max` (pool ceiling) combined with summing `used`+`idle` from `db.client.connection.count`, which is a config value vs. a live total, so not a perfect 1:1, but close enough in practice.

### 4.3 Dependency weight and stability

Same footprint pattern as `opentelemetry-jdbc`: the published POM for `opentelemetry-hikaricp-3.0:2.16.0-alpha` (`https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-hikaricp-3.0/2.16.0-alpha/opentelemetry-hikaricp-3.0-2.16.0-alpha.pom`) declares only `opentelemetry-instrumentation-api`, `opentelemetry-instrumentation-api-incubator` (alpha), and `opentelemetry-api` as compile dependencies. HikariCP itself is **not** a transitive dependency of the artifact — [`instrumentation/hikaricp-3.0/library/build.gradle.kts`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/hikaricp-3.0/library/build.gradle.kts) declares `library("com.zaxxer:HikariCP:3.0.0")`, which under the repo's Gradle conventions is a compile-only floor version, not a bundled runtime dep — music-catalogue already brings its own HikariCP 7.0.2 (`pom.xml`), well above the 3.0.0 floor.
- Same `-alpha`-only version history as `opentelemetry-jdbc`: current latest is `2.29.0-alpha` (`https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-hikaricp-3.0/maven-metadata.xml`), never released stable.
- Depends on the same `instrumentation-api-incubator` unstable API surface as §3.5.

## 5. Comparison table

| Currently bespoke | Off-the-shelf equivalent | Covers / exceeds / falls short | Note |
|---|---|---|---|
| Statement CLIENT span (`StatementExecutor.startStatementSpan`, `.java:128`) with `pgenie.statement.name` attribute | `opentelemetry-jdbc` statement instrumenter | **Falls short** | Gets you a CLIENT span with standard `db.*` attributes, but `pgenie.statement.name` (generated-class identity) has no semconv equivalent — that attribute would still need to be added by hand, e.g. via a custom `AttributesExtractor` passed into `createStatementInstrumenter`. |
| Batch span + `pgenie.statement.batch_size` (`StatementExecutor.java:142`) | `opentelemetry-jdbc` statement instrumenter, `db.operation.batch.size` | **Roughly covers** | Semconv has a recommended `db.operation.batch.size` attribute; naming differs from `pgenie.statement.batch_size` but the concept maps. |
| `pgenie.musiccatalogue.statement.duration` histogram, always-on (`MusicCatalogueSession.java:89`) | `db.client.operation.duration` via `DbClientMetrics` | **Falls short by default, covers if opted in** | Off by default (no-op unless `-Dotel.semconv-stability.opt-in=database` is set); bespoke histogram is unconditional. Off-the-shelf version also can't carry `pgenie.statement.name`/SQL-text dimensions the same way without extra extractors. |
| Transaction `INTERNAL` span with `pgenie.transaction.max_attempts`/`pgenie.transaction.read_only` (`MusicCatalogueSession.java:268`) | `opentelemetry-jdbc` transaction instrumenter (commit/rollback spans) | **Falls short** | Different shape entirely: off-the-shelf instruments individual `commit()`/`rollback()` calls (CLIENT spans), not a single span wrapping the whole retry-capable transaction body; also experimental/off by default; no pgenie-specific attempt/read-only attributes. |
| `pgenie.musiccatalogue.transaction.retries` counter, derived from commit/rollback bookkeeping (`MusicCatalogueSession.java:85`, `:282-310`) | none | **Falls short (no equivalent)** | Pure pgenie/vendor-retry-loop concept; nothing in OTel semconv models "retries of a logical transaction." Stays fully bespoke. |
| 4 HikariCP pool gauges: active/idle/pending/total (`MusicCatalogueSession.java:112-154`) | `opentelemetry-hikaricp-3.0` (`DbConnectionPoolMetrics` via `MetricsTrackerFactory`) | **Exceeds** | Covers active/idle (as one `count` metric split by state), pending, and an approximate total (via `max`); additionally provides create/wait/use-time histograms and a timeout counter that don't exist in the bespoke code today. Names differ (`db.client.connection.*` vs `pgenie.musiccatalogue.pool.connections.*`) and depend on the same semconv opt-in for the stable names. |
| `db.system`/`db.user` attributes on statement spans (`StatementExecutor.java:33-35`) | `SqlClientAttributesExtractor` (`db.system.name`, no direct `db.user` equivalent) | **Partially covers** | `db.system` maps to `db.system.name`; there's no standard `db.user` attribute in the current db-client semconv (it existed in older/experimental semconv but isn't part of the stable set) — would be dropped or need a custom extractor. |
| `healthCheck` / `session.close` spans (`MusicCatalogueSession.java:318`, `:354`) | none | **Falls short (no equivalent)** | Session-lifecycle concepts specific to the generated artifact API; no library instrumentation targets them. |

## 6. Open questions / caveats

- I have live web access (verified via `WebFetch`/direct `curl` against `raw.githubusercontent.com`, `repo1.maven.org`, and `opentelemetry.io`) and used it for every non-obvious factual claim above; nothing here is guessed from training-data memory of these artifacts.
- I read source at the `main` branch tip as of 2026-07-10, not pinned to a specific commit SHA — if the wayfinder ticket needs a reproducible pin, re-fetch and note the commit SHA (`git rev-parse main` on a clone, or the `v2.29.0` tag) at time of use, since `JdbcInstrumenterFactory`/`OpenTelemetryMetricsTrackerFactory` are explicitly marked internal/unstable and could change shape between releases.
- I did not exhaustively diff every attribute the stable-vs-legacy semconv modes produce line-by-line against the spec's full attribute table (e.g. `db.response.status_code`, `network.peer.address`) — the summarized attribute lists in §3.2 and the semconv fetch cover the ones relevant to this comparison, but a generator implementer adopting this should re-read `SqlClientAttributesExtractor` and the current [database-spans spec](https://opentelemetry.io/docs/specs/semconv/database/database-spans/) directly before committing to an attribute contract.
- The javaagent (auto-instrumentation) variants of both `jdbc` and `hikaricp-3.0` were out of scope per the research question (it specifically asked about the library/DataSource-wrapping route); they exist in the same repo under `instrumentation/jdbc/javaagent` and `instrumentation/hikaricp-3.0/javaagent` and layer bytecode-injection config on top of the same library code, so the metrics/spans/stability facts above should carry over, but this wasn't independently verified.
- I did not find or check for a Micrometer-based alternative in detail since the first-party `MetricsTrackerFactory` route (§4.1) directly answers the "is there a first-party implementation" question with yes — the micrometer-bridge alternative mentioned in the research question is moot for this repo's purposes and wasn't investigated further.
