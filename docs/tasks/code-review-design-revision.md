# Code review: design-revision implementation

Two-axis review (Standards + Spec) of `git diff 543cfde...HEAD` — the
commits implementing `docs/design-revision-plan.md` (`b5324bb`..`b56d104`;
the plan file itself was deleted in `5f2c205`, so it now lives only in git
history at `git show b56d104:docs/design-revision-plan.md`). First reviewed
2026-07-16; re-reviewed same day — no source changes in between, so prior
findings carry over, with new findings added.

## Standards

No documented repo standards exist, so all findings are Fowler-baseline
**judgement calls**; checkstyle-enforced items excluded.

- [x] **Duplicated Code** — `Telemetry.finishStatementOperation` vs
  `finishTransactionOperation`: ~20-line near-identical bodies (outcome
  ternary, attempt-count attribute, counter `.add(max(0, attempts-1))`,
  status/recordException branch, exhausted-attempts log, `span.end()`),
  differing only in keys, counter, success string. Same shape between
  `startStatementOperation` and `startTransactionOperation`. Extract the
  shared shape (or a key-set per operation kind).
- [x] **Duplicated Code** — `SessionSettings`: the `withXxx` methods each
  re-list the full 15-arg constructor call verbatim; this change's two new
  fields forced edits to every one (Shotgun Surgery in miniature). A builder
  or single copy helper would collapse it.
- [x] **Duplicated Code (worst on this axis)** — `SqlStateClassifier`: the
  `"40001"/"40P01"` literal pair appears in both `classify` and
  `isTransactionWide`; worse, `isTransactionWide` re-implements SQL
  extraction inline instead of reusing `extractSqlException`, so it
  **silently skips the cause-unwrapping** `classify` does. Share one
  constant and the helper. (See also the Spec finding on the resulting
  behavioural inconsistency.)
- [x] **Duplicated Code (minor)** — `Transaction.firstOf(List)` vs
  `firstOf(Transaction...)`: identical null-check + reduce; varargs overload
  should delegate via `Arrays.asList`.
- [x] **Duplicated Code (minor)** — `TransactionExecutor.
  NestedExecutionContext`: `execute` and `executeBatch` repeat the identical
  handle/try/succeeded/failed wrapper; extractable.
- [x] **Middle Man** — `Telemetry.startNestedStatement`/`startNestedBatch`
  are pure pass-throughs to `startStatement`/`startBatch`, and the delegated
  targets have no other caller. Cut one layer.
- [x] **Speculative Generality** — `SqlStateClassifier.classify` handles
  `failure == null` ("may be null") though every caller passes a caught
  exception. Delete the null path.
- [ ] **Data Clumps / Divergent Change** — `SessionSettings` owns both
  connection/pooling and observability settings; the five telemetry-identity
  fields (`openTelemetry, scopeName, scopeVersion, poolName, artifactName`)
  always travel together. Deliberate per the plan doc — judgement call.
- [x] **Primitive Obsession (boolean blindness)** — call sites like
  `finishStatementOperation(span, attempt, true, false, null)` pass
  positional `succeeded, retryable, failure`; an outcome concept already
  half-exists as the `OUTCOME_*` constants. An enum would also remove the
  impossible `succeeded=true, retryable=true` state.
- [x] **Mysterious Name (mild)** — `Session`'s field/param is still `config`
  after `RichPgConfig` became `SessionSettings`; likewise
  `Telemetry.forSession(SessionSettings config, …)`. Rename to `settings`.
- [x] **Mysterious literal** — `Telemetry.redactUrl`: magic `9` for
  `"password=".length()`.
- [x] **Doc rot** — `TransactionContext.java:13` javadoc links
  `Transaction#execute`; `Transaction` now exposes `run`.
- [x] **Doc rot (new)** — three javadocs cite the now-deleted plan:
  `StatementExecutor.java:14` ("Per design §2.2/§2.3"),
  `TransactionExecutor.java:16` ("per design-revision-plan §1.4/§3.1"),
  `Telemetry.java:331` ("per design §3.1"). Inline the rationale or drop the
  citations.
- [x] **Doc rot (new)** — `Telemetry.startStatementOperation` javadoc says
  "parent of all attempt spans + events", but standalone statement attempts
  emit only events (no per-attempt spans) since `aa2e518`.

Not flagged: `Telemetry` merging five observability classes is a Divergent
Change candidate, but the consolidation was the explicit point of the plan
and reads as a deliberate deep-module move.

## Spec

Findings against `docs/design-revision-plan.md`.

### Implemented but wrong

- [x] **Duration histogram never recorded per operation** (worst on this
  axis). §3.2: "`db.client.operation.duration` histogram: recorded once per
  operation." The only `durationHistogram.record` call is in
  `Telemetry.StatementHandle.finish` (`Telemetry.java:323`), reached only by
  statements/batches nested inside a transaction — standalone statements and
  transactions record nothing, while a retried transaction records once per
  statement *attempt*. Slow-query logging is likewise nested-only.
  `SessionIT.java:75` codifies "nor duration metric".
- [x] **Default retry attempts is 7, spec says 3.** §2.1: "statement retry
  attempts (default 3, sharing one constant with the transaction retry
  default)". Sharing is implemented, but
  `SessionSettings.DEFAULT_RETRY_ATTEMPTS = 7` (`SessionSettings.java:68`);
  the `defaults()` javadoc and README repeat 7.
- [x] **Statement loop retries `23505`, which the spec assigns only to the
  transaction loop.** §1.4 lists `23505` under the transaction loop only;
  `SqlStateClassifier.classify` (`SqlStateClassifier.java:49`) returns
  `SAME_CONNECTION` for `23505` unconditionally, so `Session.execute`
  retries unique violations (near-always futile — the SERIALIZABLE-masking
  rationale doesn't apply there).
- [x] **Transaction span ended twice.** `Telemetry.finishTransactionOperation`
  calls `span.end()` (`Telemetry.java:414`) and `TransactionExecutor.execute`'s
  `finally` calls `operationSpan.end()` again (`TransactionExecutor.java:62`).
  OTel tolerates it, but the first end wins, so §3.1's "COMMIT/ROLLBACK/SET
  absorbed into the transaction span's duration" doesn't hold for the
  connection-state-restore round-trips; intent is ambiguous.
- [x] **`isTransactionWide` inconsistent with `classify` on wrapped
  exceptions.** `SqlStateClassifier.isTransactionWide`
  (`SqlStateClassifier.java:64-73`) checks only a direct `SQLException`
  while `classify` unwraps one cause level: a wrapped `40001` from an `or()`
  branch is treated as savepoint-recoverable (alternative runs) yet the
  outer loop still classifies it retryable — contra §1.4's "one small pure
  classifier" consolidation.
- [x] **`Session.execute` javadoc contradicts spec and itself** — says
  statements are retried "when … declared idempotent"
  (`Session.java:58-59,77-78`), but §2.2 makes retrying always on with the
  classifier deciding, as its own next paragraph states.

### Missing / partial

- [x] **Attempt-failure events lack an attempt-number attribute.** §3.1: "a
  span event ... carrying the exception, attempt number, and attempt
  duration." `recordAttemptFailed` embeds the number only in the event
  *name*; attributes are hand-rolled `exception.message`/`exception.type`
  (no stacktrace, not `recordException` semantics).
- [x] **`artifactName` not on every span.** §2.1: "attached as
  `pgenie.artifact.name` on every span". The health-check span
  (`Telemetry.java:417-423`) and `session.close` span
  (`Telemetry.java:436-442`) omit it.
- [ ] §3.1 names final attributes `attempt_count`, `outcome`; implementation
  emits `pgenie.statement.attempt_count` / `pgenie.transaction.attempt_count`
  (minor; the telemetry surface was allowed to break).

### Scope creep

- [x] Extra public overload `Session.executeTransaction(Transaction, Span)`
  (`Session.java:120`) — §2.2 lists exactly 7 methods.
- [ ] Unrequested `mockito-core` test dependency in `pom.xml`. Left in place:
  it now backs `TelemetryTest`, `StatementExecutorTest`, and
  `TransactionCompositionTest` (mocking `HikariPoolMXBean`, `Statement`,
  `Connection`, `TransactionContext`/`Savepoint`), so removing it means
  hand-rolling fakes for several large JDK/Hikari interfaces — a rewrite out
  of proportion to a "scope creep" finding. Flagging as a deliberate
  trade-off rather than fixing blind.

### Minor

- [x] `Session` keeps its own SLF4J logger though §1.2 says Telemetry owns
  "the SLF4J logger".

Verified conforming: layering inversion, package flattening, package-private
`Telemetry`/`TransactionContext`/`toHikariDataSource`,
`StatementSettings`/`executeRetryable` deletion, slimmed
`TransactionSettings(isolationLevel, readOnly)`, counted attempts, span
shape (INTERNAL transaction span with CLIENT children and attempt-failed
events), separate retry counters, pool gauges, healthCheckTimeout 2 s /
closeDrainDeadline 10 s defaults, db-only contract documented on
`Transaction`, in-memory-exporter tests, README/CHANGELOG.
