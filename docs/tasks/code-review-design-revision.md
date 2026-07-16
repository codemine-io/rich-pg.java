# Code review: design-revision implementation

Two-axis review (Standards + Spec) of `git diff 543cfde...HEAD` — the 7
commits implementing `docs/design-revision-plan.md`
(`b5324bb`..`b56d104`). Reviewed 2026-07-16.

## Standards

No documented repo standards exist, so all findings are Fowler-baseline
**judgement calls**; checkstyle-enforced items excluded.

- [ ] **Duplicated Code** — `Telemetry.finishStatementOperation` vs
  `finishTransactionOperation`: ~20-line near-identical bodies (outcome
  ternary, attempt-count attribute, counter `.add(max(0, attempts-1))`,
  status/recordException branch, exhausted-attempts log, `span.end()`),
  differing only in keys, counter, success string. Extract the shared shape.
- [ ] **Duplicated Code** — `SessionSettings`: 15 `withXxx` methods each
  re-listing the full 15-arg constructor call verbatim.
- [ ] **Duplicated Code (worst on this axis)** — `SqlStateClassifier`: the
  `"40001"/"40P01"` literal pair appears in both `classify` and
  `isTransactionWide`; worse, `isTransactionWide` re-implements SQL
  extraction inline instead of reusing `extractSqlException`, so it
  **silently skips the cause-unwrapping** `classify` does. Share one
  constant and the helper.
- [ ] **Duplicated Code (minor)** — `Transaction.firstOf(List)` vs
  `firstOf(Transaction...)`: identical null-check + reduce; varargs overload
  should delegate via `Arrays.asList`.
- [ ] **Middle Man** — `Telemetry.startNestedStatement`/`startNestedBatch`
  are pure pass-throughs to `startStatement`/`startBatch`. Call the real
  targets directly.
- [ ] **Data Clumps / Divergent Change** — `SessionSettings` owns both
  connection/pooling and observability settings; the five telemetry-identity
  fields (`openTelemetry, scopeName, scopeVersion, poolName, artifactName`)
  always travel together. Deliberate per the plan doc — judgement call.
- [ ] **Primitive Obsession (boolean blindness)** — call sites like
  `finishStatementOperation(span, attempt, true, false, null)` pass
  positional `succeeded, retryable, failure`; an outcome concept already
  half-exists as the `OUTCOME_*` constants.
- [ ] **Mysterious literal** — `Telemetry.redactUrl`: magic `9` for
  `"password=".length()`.
- [ ] **Doc rot** — `TransactionContext.java:13` javadoc links
  `Transaction#execute`; `Transaction` now exposes `run`.

## Spec

Findings against `docs/design-revision-plan.md`.

### Implemented but wrong

- [ ] **Duration histogram never recorded per operation** (worst on this
  axis). §3.2: "`db.client.operation.duration` histogram: recorded once per
  operation." `durationHistogram.record` exists only in
  `StatementHandle.finish()` (nested statements/batches);
  `finishStatementOperation` and `finishTransactionOperation` never record
  it — standalone statements and transactions record nothing, while a
  retried transaction records it once per statement *attempt*.
  `SessionIT.java:75` codifies "nor duration metric".
- [ ] **Default retry attempts is 7, spec says 3.** §2.1: "statement retry
  attempts (default 3, sharing one constant with the transaction retry
  default)". `SessionSettings.DEFAULT_RETRY_ATTEMPTS` exists but is `7`;
  README repeats 7.
- [ ] **Statement loop retries `23505`, which the spec assigns only to the
  transaction loop.** §1.4 lists `23505` under the transaction loop only;
  `SqlStateClassifier.classify` returns `SAME_CONNECTION` for `23505`
  unconditionally, so `Session.execute` retries unique violations
  (near-always futile).

### Missing / partial

- [ ] **Attempt-failure events lack an attempt-number attribute.** §3.1: "a
  span event ... carrying the exception, attempt number, and attempt
  duration." `recordAttemptFailed` embeds the number only in the event
  *name*; attributes are hand-rolled `exception.message`/`exception.type`
  (no stacktrace, not `recordException` semantics).
- [ ] **`artifactName` not on every span.** §2.1: "attached as
  `pgenie.artifact.name` on every span". Health-check and `session.close`
  spans omit it.
- [ ] §3.1 names final attributes `attempt_count`, `outcome`; implementation
  emits `pgenie.statement.attempt_count` / `pgenie.transaction.attempt_count`
  (minor; the telemetry surface was allowed to break).

### Scope creep

- [ ] Extra public overload `Session.executeTransaction(Transaction, Span)`
  — §2.2 lists exactly 7 methods.
- [ ] Unrequested `mockito-core` test dependency in `pom.xml`.

### Minor

- [ ] `Session` keeps its own SLF4J logger though §1.2 says Telemetry owns
  "the SLF4J logger".

Verified conforming: layering inversion, package flattening, package-private
`Telemetry`/`TransactionContext`, `StatementSettings`/`executeRetryable`
deletion, slimmed `TransactionSettings`, counted attempts, span shape,
counters/gauges, README/CHANGELOG.
