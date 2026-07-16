# Upcoming

## Breaking

- `RichPgConfig` renamed to `SessionSettings`, matching the `*Settings` naming convention. The `toHikariDataSource()` method is now package-private.
- `SessionSettings` gains two new fields: `healthCheckTimeout` (default 2 seconds) and `closeDrainDeadline` (default 10 seconds).
- `artifactName` is now wired: attached as `pgenie.artifact.name` on every span and included in the "Session opened" log line.
- The `io.codemine.java.richpg.transaction` package is flattened: `Transaction`, `ExecutionContext`, `TransactionSettings`, and `IsolationLevel` move into `io.codemine.java.richpg`. `TransactionContext` is demoted to package-private.
- `TransactionSettings` no longer carries `maxAttempts` — retry attempts are now session-wide via `SessionSettings.retryAttempts()` (default 7).
- `StatementSettings` deleted entirely. Per-call retry attempt override no longer exists.
- `Session.executeRetryable` deleted. `Session.execute` always retries — there is no non-retrying entry point.
- The `io.codemine.java.richpg.observability` package is deleted. Telemetry is now internal to a single package-private `Telemetry` class. Span/metric shape changed: standalone retried statements get one `CLIENT` span with per-attempt failure span events; statements inside transactions get single-attempt `CLIENT` spans with no retry events.
