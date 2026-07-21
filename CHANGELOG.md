# Upcoming

## Breaking

- `RichPgConfig` renamed to `SessionSettings`, matching the `*Settings` naming convention. The `toHikariDataSource()` method is now package-private.
- `SessionSettings` gains two new fields: `healthCheckTimeout` (default 2 seconds) and `closeDrainDeadline` (default 10 seconds).
- `artifactName` is now wired: attached as `pgenie.artifact.name` on every span and included in the "Session opened" log line.
- The `io.codemine.java.richpg.transaction` package is flattened: `Transaction`, `ExecutionContext`, `TransactionSettings`, and `IsolationLevel` move into `io.codemine.java.richpg`. `TransactionContext` is demoted to package-private.
- `TransactionSettings` no longer carries `maxAttempts` — retry attempts are now session-wide via `SessionSettings.retryAttempts()` (default 3).
- `StatementSettings` deleted entirely. Per-call retry attempt override no longer exists.
- `Session.executeRetryable` deleted. `Session.execute` always retries — there is no non-retrying entry point.
- The `io.codemine.java.richpg.observability` package is deleted. Telemetry is now internal to a single package-private `Telemetry` class. Span/metric shape changed: standalone retried statements get one `CLIENT` span with per-attempt failure span events; statements inside transactions get single-attempt `CLIENT` spans with no retry events.
- Batch execution's statement-batching helper moved from `postgresql-jdbc`'s public `StatementBatch` to an internal, package-private class in `rich-pg`. `executeBatch` now also rejects a batch whose statements disagree on `operationName()`/`collectionName()`, not just `sql()`.
- `db.client.operation.duration`'s `error.type` attribute on standalone statements and transactions now carries the terminal failure's SQLSTATE (or `unknown` if it has none), not the retry-outcome label (`retries_exhausted`/`non_retryable_failure`). The Grafana dashboard's "by outcome" panels and the two alert rules now group/fire on SQLSTATE instead. `pgenie.statement.outcome`/`pgenie.transaction.outcome` span attributes are unaffected.

## Non-breaking

- `Session` gains standalone (non-transactional) batch execution: `executeBatch(Iterable<? extends Statement<R>>)` and `executeBatch(Iterable<? extends Statement<R>>, Span)`, mirroring `execute(Statement)`'s shape. A batch is one JDBC `executeBatch()` call on a pooled connection and is never retried.
- `db.client.operation.duration` now also carries `error.type` on standalone batches that fail (the failure's SQLSTATE, or `unknown`), omitted on success. Batches executed inside a transaction still carry no `error.type`, matching statements executed inside a transaction.

## Fixes

- `db.client.operation.duration` is now recorded once per standalone statement and per transaction, not just for statements nested inside a transaction; slow-query logging likewise now applies to those operations.
- The statement retry loop no longer retries `23505` (unique violation) — only the transaction retry loop does, since a statement-level retry has no savepoint to fall back to and would just repeat the same conflicting write.
- The transaction operation span is no longer ended twice; ending now happens once, after the connection-state restore, so `COMMIT`/`ROLLBACK`/`SET` round-trips are included in its duration.
- `SqlStateClassifier.isTransactionWide` now unwraps one cause level like `classify` does, so a wrapped `40001`/`40P01` is treated consistently by both the `Transaction#or` savepoint path and the transaction retry loop.
- Attempt-failure span events now carry an explicit attempt-number attribute and use `Span#recordException` (including the stack trace) instead of hand-rolled exception attributes.
- `pgenie.artifact.name` is now attached to the health-check and `session.close` spans, matching every other span.
- `Session.executeTransaction(Transaction, Span)` — an unintended extra overload bypassing default `TransactionSettings` — is removed; use `executeTransaction(Transaction, TransactionSettings, Span)`.
