# Design revision plan

Status: agreed, not yet implemented.

This document captures the agreed redesign of rich-pg. The current design was
assessed as noisy (config boilerplate, overload combinatorics, duplicated
constants and helpers) and sketchy (execution semantics living in the
`observability` package, attempt counts inferred instead of counted, SQLSTATE
knowledge scattered across four sites in three variants). Full freedom was
granted: both the public API and the telemetry surface (span names, metric
names, attribute keys) may break.

## 1. Structure and layering

### 1.1 Invert the observability layering

Today the `observability` package owns execution semantics:
`TransactionObservability.TransactionObservation` implements
`TransactionContext` (delegating commits/rollbacks, executing statements and
batches), and retry-domain logic (`classify`, `isRetryableFailure`, the
SQLSTATE tables) lives in observability classes, while the executors in the
root package are thin shells.

After the revision, core code (session, executors, retry loops, SQLSTATE
classification) owns all execution semantics and *notifies* a passive
telemetry emitter at well-defined moments (operation started, attempt failed,
committed after N attempts, ...). Core never depends on telemetry types for
control flow. The `observability` package disappears.

### 1.2 One telemetry emitter

A single concrete, package-private `Telemetry` class (no interface, no no-op
variant — OpenTelemetry's API is already a null-object design) owns:

- the tracer, the one `db.client.operation.duration` histogram, the retry
  counters, the pool gauges;
- the SLF4J logger, dbUser, slow-query threshold, artifact name.

It is built once from the session settings. Its methods start operations and
return thin per-operation handles carrying only what varies per operation
(span, start time, statement name). All attribute-key constants live in this
one file. The duplicated `extractSqlException`, `DB_SYSTEM`, and
`db.system.name` definitions, the 6-tuple field threading, and the 7–9
parameter telescoping factories all disappear.

### 1.3 Flatten the package layout

Everything moves to `io.codemine.java.richpg`. The `richpg.transaction`
subpackage is dissolved: with a public API of roughly seven types there is no
scale or access-control reason for internal taxonomy, and flattening lets the
package-private executors/telemetry collaborate with transaction types.

Public API after the revision:

- `Session`
- `SessionSettings` (renamed from `RichPgConfig`, see 2.1)
- `Transaction`, `ExecutionContext`, `TransactionSettings`, `IsolationLevel`
- (`Statement<R>` remains provided by `io.codemine.java.postgresql:jdbc`)

`TransactionContext` is demoted to package-private if nothing external needs
it — verify during implementation.

### 1.4 Move the transaction retry loop out of `Transaction`

`Transaction.execute`'s retry loop moves into the session-side executor.
`Transaction` becomes a pure composition algebra: `run`, `andThen`, `map`,
`flatMap`, `or`, `empty` — no loop, not independently executable.

Consequences:

- Attempt counts are counted directly by the loop instead of being inferred
  by intercepting `rollback()` calls (`Math.max(1, rollbackCount)` goes away).
- Outcome labels (`retries_exhausted` vs `non_retryable_failure`) come from
  the loop's actual knowledge, not from re-classifying the final exception
  after the fact (`isRetryableFailure`-as-outcome-guess goes away).
- SQLSTATE knowledge consolidates into one small pure classifier (unit
  testable without OTel) used by:
  - the transaction retry loop (`40001`, `40P01`, `23505`);
  - `Transaction.or()` (`40001`, `40P01` rethrown as transaction-wide);
  - the statement retry loop (`40001`/`40P01` retried on the same
    connection; SQLSTATE class `08` retried on a fresh connection only when
    the statement is idempotent).

## 2. Public API

### 2.1 `RichPgConfig` → `SessionSettings`

- Renamed to `SessionSettings`, matching the `*Settings` convention.
- Stays a record with hand-written withers (builder rejected).
- `toHikariDataSource()` becomes package-private.
- `artifactName` is kept **and wired**: attached as `pgenie.artifact.name` on
  every span and included in the "Session opened" log line. No silent no-op
  knobs.
- New fields, each with a default:
  - health-check query timeout (default 2 s);
  - close drain deadline (default 10 s);
  - statement retry attempts (default 3, sharing one constant with the
    transaction retry default so they cannot drift apart).

### 2.2 `Session` surface

Statement retrying is always on (`executeRetryable` is deleted; there is no
non-retrying entry point — the classifier already guarantees retries are only
attempted when safe). Retry budgets come from `SessionSettings`. The explicit
parent span is a trailing-overload escape hatch for async cases where OTel
context propagation is impractical; all other parenting uses `Span.current()`.

```java
<R> R execute(Statement<R> statement)
<R> R execute(Statement<R> statement, Span parentSpan)
<R> R executeTransaction(Transaction<R> transaction)
<R> R executeTransaction(Transaction<R> transaction, TransactionSettings settings)
<R> R executeTransaction(Transaction<R> transaction, TransactionSettings settings, Span parentSpan)
boolean healthCheck()
void close()
```

### 2.3 `StatementSettings` deleted

With the default attempt count in `SessionSettings` and the parent span as an
overload, the record would hold only exotic knobs; it is removed. Per-call
attempt override is consciously given up.

### 2.4 `TransactionSettings` slims to pure semantics

`TransactionSettings(isolationLevel, readOnly)` — no `maxAttempts` (session
policy now), no `Span` (tracing is not transaction semantics), no OTel
dependency.

### 2.5 `Transaction` documents the db-only contract

The retry loop re-executes transaction bodies, so bodies must be pure
database logic: re-executable, no non-database I/O, no external side effects.
This becomes an explicit, prominent documented contract on `Transaction`
(the transaction-level analog of `Statement.idempotent()`).

## 3. Telemetry model ("Philosophy 2")

One span per logical operation; retry attempts are span events, not spans.

### 3.1 Span structure

- **Statement** (standalone `execute`): a single `CLIENT` span covering all
  attempts, parented to the ambient context or the explicit `parentSpan`.
- **Transaction**: a single `INTERNAL` span covering all attempts.
  `INTERNAL`, not `CLIENT`: a transaction is N round-trips, and a `CLIENT`
  transaction span would double-count the app→PostgreSQL edge in service
  maps and pollute client-latency accounting. The `CLIENT` children remain
  the clean latency signal.
- **Statements inside a transaction**: one single-attempt `CLIENT` span
  each, children of the transaction span. Statement-level retry never
  engages inside a transaction — the transaction is the retry unit. When a
  transaction retries, the next attempt's statement spans appear as new
  siblings under the same transaction span.
- **Failed attempts** (statement or transaction): a span event on the
  operation's span carrying the exception, attempt number, and attempt
  duration.
- Final attributes on the operation span: `attempt_count`, `outcome`
  (`succeeded`/`committed`, `retries_exhausted`, `non_retryable_failure`),
  plus `pgenie.artifact.name`.
- The `statement.retry` wrapper span is deleted.
- `COMMIT`/`ROLLBACK`/`SET` round-trips get no spans; they are absorbed into
  the transaction span's duration.
- Health check stays a `CLIENT` span; `session.close` stays `INTERNAL`.

Example trace shape:

```
http-request                        SERVER (caller's)
└── transaction                     INTERNAL — covers all attempts
    ├── insertArtist                CLIENT — attempt 1
    ├── insertAlbum                 CLIENT — attempt 1 (fails, 40001)
    │     event: attempt 1 failed (exception, duration)
    ├── insertArtist                CLIENT — attempt 2
    └── insertAlbum                 CLIENT — attempt 2
```

### 3.2 Metrics

- `db.client.operation.duration` histogram: recorded once per operation.
- Retry counters stay separate: `pgenie.statement.retries` and
  `pgenie.transaction.retries` are distinct signals.
- `pgenie.pool.connections.{active,idle,pending,total}` gauges unchanged.

## 4. Tests

Observability tests are rewritten against OpenTelemetry in-memory exporters
(assert on emitted spans/metrics), not on interactions with internal
telemetry types, so they survive future internal refactoring.

## 5. Consciously rejected alternatives

| Alternative | Reason rejected |
| --- | --- |
| Decorator/listener interfaces for observability | No second implementation exists; OTel API is already no-op capable |
| Builder for `SessionSettings` | Owner prefers record + withers |
| Dropping `artifactName` | Kept and wired instead |
| Per-attempt spans (Philosophy 1) | Consumers reason in operations; events keep volume minimal while preserving attempt detail |
| `CLIENT` transaction span with documented db-only bodies | Kind means "one request boundary"; nested `CLIENT` spans double-count the DB edge |
| Keeping `Transaction.execute` standalone | Its loop is what forced attempt-count inference; only `Session` runs transactions |
