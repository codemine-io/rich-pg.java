# ADR 0001: Passive per-statement-class health tracking

## Status

Accepted

## Context

`Session` already exposes `healthCheck()`, backed by a periodic active probe
(`select 1` against a pooled connection, see `Session.probe()`). That probe
answers "is the database reachable and the pool not saturated" — but it
cannot detect a failure mode where the database is perfectly reachable while
the application's compiled-in expectations about the schema no longer match
reality: a column was dropped or retyped, a table was renamed, or a codec's
assumptions about a result shape no longer hold. A `select 1` succeeds
regardless of any of that. This is a **readiness gap**: the probe can be
green while every real statement of a given kind is failing.

Real statement executions already prove or disprove this contract every time
they run. `Statement<R>.execute(Connection)` (an external interface from
`io.codemine.java.postgresql:jdbc`) performs SQL execution
(`ps.execute()`/`ps.executeUpdate()`) followed by decoding
(`decodeResultSet()`/`decodeAffectedRows()`) inside one undifferentiated
try block. A failure during either step is direct evidence that a specific
kind of statement no longer matches the live schema or the live codec
contract — evidence the active probe structurally cannot produce, because it
runs a fixed, unrelated query.

`rich-pg` already has a failure-classification layer, `ClassifiedSqlFailure`
+ `RetryStrategy`, but it exists to answer a different question ("should
this specific call be retried") and deliberately only distinguishes the
SQLSTATE classes relevant to that (`40001`/`40P01` serialization/deadlock,
`08` connection exceptions, `23505` unique violation). Schema-drift failures
(SQLSTATE class `42` — undefined table/column, type mismatch — and `22000`
decode/shape mismatches) are not distinguished from arbitrary
non-retryable failures today, and retry-worthiness is orthogonal to
"is this integrational drift" — e.g. `42P01` is not retryable but is
drift; `23505` is transaction-retryable but is not drift.

## Decision

1. **Signal source**: track outcomes of real statement executions, in
   addition to (not instead of) the existing active probe. The two signals
   detect different failure classes (unreachable database vs. drifted
   schema/codec contract) and are combined into one overall verdict.

2. **Identity**: per-statement-class, keyed by `statement.getClass()`. Not
   per-SQL-text and not per-call-site — the question being answered is
   "is this kind of operation broken," not "did this one call succeed."

3. **Representation**: a concurrent set of broken statement classes
   (`Set<Class<?>>`, e.g. backed by `ConcurrentHashMap.newKeySet()`), not a
   three-state map. Presence in the set means broken. Absence means either
   "never executed" or "last known execution succeeded" — both collapse to
   the same state because neither implies anything different for the
   overall verdict, and it makes recovery free: a later successful
   execution simply removes the class from the set.

4. **Classification**: a new classifier, independent of
   `ClassifiedSqlFailure`/`RetryStrategy`. It classifies a failure as
   integrational drift if it originates from SQL execution
   (SQLSTATE class `42`) or from decoding (SQLSTATE `22000`, or an
   unclassifiable/non-`SQLException` throwable escaping decode — treated as
   evidence of drift rather than silently ignored). Business-logic outcomes
   (e.g. `23505`) never mark a statement broken.

5. **Attribution boundary**: only the statement whose own execution
   actually threw is marked. A transaction that rolls back for an unrelated
   reason (another statement's conflict, a deliberate application-level
   abort) does not taint statements that executed cleanly within it —
   rollback is a transactional/business outcome, not evidence that a given
   statement's SQL or codec is broken.

6. **Aggregation**: any broken statement class makes the overall verdict
   unhealthy. This is a readiness signal; a false negative (missing real
   drift) is worse than a noisy false positive.

7. **Mechanism**: inline `Statement.execute(Connection)`'s body at the call
   sites that currently invoke it polymorphically (`Session.execute`, and
   the `ExecutionContext` implementations used inside transactions), so
   that the SQL-execution step and the decode step can be caught and
   classified separately and structurally, rather than inferred from a
   SQLSTATE convention (`AgnosticCodec` wrapping decode failures as
   SQLSTATE `22000` is a convention of the current codec implementation,
   not a guaranteed contract of `Statement`). No shared abstraction is
   introduced between the two inlined call sites for this iteration — the
   duplication is accepted as visible and collapsible later, rather than
   guessing at a shared shape now.

8. **Structure**: a new collaborator (e.g. `StatementHealthTracker`) owns
   the set and the classification, and `Session` composes its snapshot with
   the probe's `healthy` flag — consistent with this codebase's existing
   pattern of one concern per class (`RetryStrategy`, `ClassifiedSqlFailure`,
   `ExecutionContext`) rather than growing `Session` itself.

9. **API**: `healthCheck()`'s return type changes from a bare boolean to a
   richer verdict that distinguishes "probe failed" from "schema drift
   detected in these statement classes" — no backward-compatibility
   constraint applies.

10. **Upstream dependency**: inlining `Statement.execute()`'s body
    duplicates logic owned by an external library
    (`io.codemine.java.postgresql:jdbc`) that is not itself designed to be
    decomposed this way — its `execute()` is a single `default` method with
    no seam between the execution step and the decode step.
    [codemine-io/postgresql-jdbc.java#3](https://github.com/codemine-io/postgresql-jdbc.java/issues/3)
    proposes removing that default method in favor of an interface shape
    that exposes execution and decoding as separately observable steps.
    This ADR's inlining is explicitly provisional pending that design
    conversation.

## Consequences

**Accepted tradeoffs:**

- Coverage is contingent on traffic. A statement class that hasn't executed
  recently gives no signal, even if it would fail if called — this
  complements but does not replace the active probe's unconditional
  cadence.
- No decay/expiry. A statement marked broken stays broken until it executes
  again and succeeds; there is no time-based self-healing.
- Two call sites (`Session.execute`, `ExecutionContext` implementations)
  each carry an inlined, duplicated copy of `Statement.execute()`'s logic
  until upstream issue #3 resolves and offers a real seam to collapse them
  through.
- This is additional complexity in a library whose stated goal is to keep
  "non-domain plumbing" simple; the tracker, the new classifier, and the
  inlined execution logic are a real increase in surface area, taken on
  because the specific gap (schema drift invisible to the active probe) is
  judged to be worth it.

**Explicitly out of scope for this iteration:**

- Any shared abstraction between the two inlined execution sites.
- Any change to `ClassifiedSqlFailure`/`RetryStrategy`'s retry semantics.
- Any weighting/percentage-based aggregation, or minimum-sample-size gating,
  beyond "any broken class -> unhealthy."
