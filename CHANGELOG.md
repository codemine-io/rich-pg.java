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

## Fixes

- `db.client.operation.duration` is now recorded once per standalone statement and per transaction, not just for statements nested inside a transaction; slow-query logging likewise now applies to those operations.
- The statement retry loop no longer retries `23505` (unique violation) — only the transaction retry loop does, since a statement-level retry has no savepoint to fall back to and would just repeat the same conflicting write.
- The transaction operation span is no longer ended twice; ending now happens once, after the connection-state restore, so `COMMIT`/`ROLLBACK`/`SET` round-trips are included in its duration.
- `SqlStateClassifier.isTransactionWide` now unwraps one cause level like `classify` does, so a wrapped `40001`/`40P01` is treated consistently by both the `Transaction#or` savepoint path and the transaction retry loop.
- Attempt-failure span events now carry an explicit attempt-number attribute and use `Span#recordException` (including the stack trace) instead of hand-rolled exception attributes.
- `pgenie.artifact.name` is now attached to the health-check and `session.close` spans, matching every other span.
- `Session.executeTransaction(Transaction, Span)` — an unintended extra overload bypassing default `TransactionSettings` — is removed; use `executeTransaction(Transaction, TransactionSettings, Span)`.
