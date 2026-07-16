# rich-pg Module Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the shallow `TransactionContext` module, flatten the two single-method executor objects to static methods, and unify/de-duplicate the `Telemetry` handle lifecycle — reducing structural surface without changing observable behavior.

**Architecture:** These are behavior-preserving refactors of code that already has a test safety net (`mvn verify` runs unit tests via Surefire and `*IT` integration tests via Failsafe against a real Postgres per `AbstractDatabaseIT`). Each task ends green on `mvn verify`. There is no new feature behavior; the existing tests are the specification. Where a task changes a seam, its tests are migrated in the same task so the suite stays green and compiling at every commit.

**Tech Stack:** Java, Maven, JUnit 5, AssertJ, Mockito, OpenTelemetry SDK (in-memory span exporter for assertions), SLF4J, HikariCP.

## Global Constraints

- Package: `io.codemine.java.richpg`; source under `src/main/java/io/codemine/java/richpg/`, tests under `src/test/java/io/codemine/java/richpg/`. Copy paths verbatim.
- Build/verify command from repo root: `mvn verify`. Integration tests (`*IT.java`) require a reachable Postgres per `AbstractDatabaseIT`; run them as part of `verify`.
- Every source class carries a top-of-file Javadoc describing its role (existing convention). Preserve this on new/changed classes.
- `docs/dependency-graph.mmd` (Mermaid) is the source of truth for module structure. Any task that adds, removes, or rewires a module MUST update it in the same commit (per `AGENTS.md`).
- No behavior change is intended by any task. If a task makes an existing test fail on assertions (not compilation), stop — that is a regression, not an expected edit.
- The public API surface (`Session`, `ExecutionContext`, `Transaction`, `SessionSettings`, `TransactionSettings`, `IsolationLevel`) must not change. All classes changed below except `ExecutionContext` are package-private.

---

## File Structure

- `TransactionContext.java` — **deleted** (Task 1). Its boundary-control methods were 1:1 `Connection` pass-throughs; its execute/batch/savepoint behavior moves to `ConnectionExecutionContext`.
- `ConnectionExecutionContext.java` — **new** (Task 1). A plain `ExecutionContext` backed by a `Connection` (execute / executeBatch / savepoints only). One responsibility: run body operations against a connection.
- `TransactionExecutor.java` — modified in Tasks 1, 3, 4. Owns the transaction retry loop; performs boundary control (auto-commit/isolation/read-only/commit/rollback) directly on the `Connection`.
- `StatementExecutor.java` — modified in Tasks 2, 4. Owns the standalone-statement retry loop.
- `Telemetry.java` — modified in Tasks 4, 5. The single instrumentation surface; operation handles become `AutoCloseable`, span construction is de-duplicated.
- `Session.java` — modified in Tasks 2, 3 (executor call sites).
- `ConnectionSupplier.java` — **unchanged**. It is a real seam (two adapters: `dataSource::getConnection` in prod, `supplied::poll` in `StatementExecutorTest`). No task touches it.

---

## Task 1: Replace TransactionContext with ConnectionExecutionContext

Removes the shallow `TransactionContext` interface. Boundary-control ops (auto-commit, isolation, read-only, commit, rollback) move onto the raw `Connection` inside `TransactionExecutor`; body ops (execute/batch/savepoints) move to a new package-private `ConnectionExecutionContext`. Must be atomic — deleting a referenced type has to compile in one commit.

**Files:**
- Create: `src/main/java/io/codemine/java/richpg/ConnectionExecutionContext.java`
- Modify: `src/main/java/io/codemine/java/richpg/TransactionExecutor.java`
- Delete: `src/main/java/io/codemine/java/richpg/TransactionContext.java`
- Modify: `src/main/java/io/codemine/java/richpg/Transaction.java` (Javadoc only)
- Rename+Modify test: `src/test/java/io/codemine/java/richpg/TransactionContextTest.java` → `ConnectionExecutionContextTest.java`
- Modify test: `src/test/java/io/codemine/java/richpg/ExecutionContextIT.java`
- Modify test: `src/test/java/io/codemine/java/richpg/TransactionCompositionTest.java`
- Modify: `docs/dependency-graph.mmd`

**Interfaces:**
- Consumes: `ExecutionContext` (public: `execute`, `executeBatch`, `setSavepoint`, `rollback(Savepoint)`, `releaseSavepoint`), `io.codemine.java.postgresql.jdbc.Statement`, `io.codemine.java.postgresql.jdbc.StatementBatch`.
- Produces: `ConnectionExecutionContext` (package-private final class) with constructor `ConnectionExecutionContext(Connection connection)` implementing `ExecutionContext`. `TransactionExecutor.execute(...)` signature is unchanged.

- [ ] **Step 1: Create `ConnectionExecutionContext`**

Create `src/main/java/io/codemine/java/richpg/ConnectionExecutionContext.java`:

```java
package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.StatementBatch;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Objects;

/**
 * A plain {@link ExecutionContext} backed directly by a JDBC {@link Connection}.
 *
 * <p>Executes statements and manages savepoints straight against the connection. It holds no
 * transaction-boundary control (commit/rollback, auto-commit, isolation level, read-only): those are
 * performed by {@link TransactionExecutor} on the connection itself, so transaction bodies — which
 * only ever see an {@link ExecutionContext} — cannot reach them.
 */
final class ConnectionExecutionContext implements ExecutionContext {

  private final Connection connection;

  ConnectionExecutionContext(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  @Override
  public <R> R execute(Statement<R> statement) throws SQLException {
    return statement.execute(connection);
  }

  @Override
  public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) throws SQLException {
    return new StatementBatch<>(statements).execute(connection);
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    return connection.setSavepoint();
  }

  @Override
  public void rollback(Savepoint savepoint) throws SQLException {
    connection.rollback(savepoint);
  }

  @Override
  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    connection.releaseSavepoint(savepoint);
  }
}
```

- [ ] **Step 2: Rewrite `TransactionExecutor` to use the `Connection` directly**

Replace the entire body of `src/main/java/io/codemine/java/richpg/TransactionExecutor.java` with the following (only the `TransactionContext` usages change: boundary ops move to `connection`, and `NestedExecutionContext` now decorates a `ConnectionExecutionContext`):

```java
package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.StatementBatch;
import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes {@link Transaction} instances against a connection, owning the retry loop, attempt
 * counting and transaction-level telemetry.
 *
 * <p>Statements executed via the {@link ExecutionContext} passed to the transaction body run
 * directly against the connection, once per attempt, each wrapped in its own single-attempt CLIENT
 * span parented to the transaction span. Statement-level retry never engages inside a transaction.
 */
final class TransactionExecutor {

  private final Telemetry telemetry;

  TransactionExecutor(Telemetry telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
  }

  <R> R execute(
      Transaction<R> transaction,
      TransactionSettings settings,
      int maxAttempts,
      Connection connection,
      Span parentSpan)
      throws SQLException {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(connection, "connection");

    Telemetry.TransactionOperationHandle operation =
        telemetry.startTransactionOperation(settings, maxAttempts, parentSpan);
    boolean originalAutoCommit = connection.getAutoCommit();
    int originalIsolation = connection.getTransactionIsolation();
    boolean originalReadOnly = connection.isReadOnly();

    connection.setAutoCommit(false);
    connection.setTransactionIsolation(settings.isolationLevel().jdbcLevel());
    connection.setReadOnly(settings.readOnly());

    try (var scope = operation.span().makeCurrent()) {
      return runAttempts(transaction, maxAttempts, connection, operation);
    } finally {
      try {
        connection.setAutoCommit(originalAutoCommit);
        connection.setTransactionIsolation(originalIsolation);
        connection.setReadOnly(originalReadOnly);
      } catch (SQLException ignoredRestoreFailure) {
        // best-effort restore; the primary outcome (success or the original failure) already
        // determined what propagates out of runAttempts
      }
      operation.recordDurationAndEnd();
    }
  }

  private <R> R runAttempts(
      Transaction<R> transaction,
      int maxAttempts,
      Connection connection,
      Telemetry.TransactionOperationHandle operation)
      throws SQLException {
    ExecutionContext instrumentedContext =
        new NestedExecutionContext(new ConnectionExecutionContext(connection), operation.span());
    for (int attempt = 1; ; attempt++) {
      long attemptStart = System.nanoTime();
      try {
        R result = transaction.run(instrumentedContext);
        connection.commit();
        operation.finish(attempt, Telemetry.Outcome.SUCCEEDED, null);
        return result;
      } catch (Exception e) {
        try {
          connection.rollback();
        } catch (SQLException suppressed) {
          e.addSuppressed(suppressed);
        }
        Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStart);
        boolean retryable = new ClassifiedSqlFailure(e).isTransactionRetryable();
        if (!retryable || attempt >= maxAttempts) {
          SQLException failure = e instanceof SQLException sqlException ? sqlException : null;
          operation.finish(
              attempt,
              retryable
                  ? Telemetry.Outcome.RETRIES_EXHAUSTED
                  : Telemetry.Outcome.NON_RETRYABLE_FAILURE,
              e);
          if (failure != null) {
            throw failure;
          }
          throw new SQLException("Transaction failed", e);
        }
        telemetry.recordAttemptFailed(operation.span(), attempt, e, attemptDuration);
      }
    }
  }

  /**
   * Executes statements against a delegate {@link ExecutionContext}, wrapping each in one
   * single-attempt CLIENT span; no statement-level retry.
   */
  private final class NestedExecutionContext implements ExecutionContext {
    private final ExecutionContext delegate;
    private final Span transactionSpan;

    NestedExecutionContext(ExecutionContext delegate, Span transactionSpan) {
      this.delegate = delegate;
      this.transactionSpan = transactionSpan;
    }

    @Override
    public <R> R execute(Statement<R> statement) throws SQLException {
      return traced(
          telemetry.startStatement(statement, transactionSpan), () -> delegate.execute(statement));
    }

    @Override
    public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements)
        throws SQLException {
      List<Statement<R>> list = new ArrayList<>();
      statements.forEach(list::add);
      if (list.isEmpty()) {
        return List.of();
      }
      StatementBatch<R> batch = new StatementBatch<>(list);
      return traced(
          telemetry.startBatch(batch, list.get(0), transactionSpan),
          () -> delegate.executeBatch(list));
    }

    private <R> R traced(Telemetry.StatementHandle handle, SqlSupplier<R> action)
        throws SQLException {
      try {
        R result = action.get();
        handle.succeeded();
        return result;
      } catch (SQLException e) {
        handle.failed(e);
        throw e;
      }
    }

    @FunctionalInterface
    private interface SqlSupplier<R> {
      R get() throws SQLException;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
      return delegate.setSavepoint();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
      delegate.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
      delegate.releaseSavepoint(savepoint);
    }
  }
}
```

- [ ] **Step 3: Delete `TransactionContext.java`**

```bash
git rm src/main/java/io/codemine/java/richpg/TransactionContext.java
```

- [ ] **Step 4: Fix the `Transaction` Javadoc reference**

In `src/main/java/io/codemine/java/richpg/Transaction.java`, the class Javadoc first line reads:

```
 * A unit of work run atomically against a {@link TransactionContext}.
```

Change it to:

```
 * A unit of work run atomically against an {@link ExecutionContext}.
```

- [ ] **Step 5: Migrate the unit test to `ConnectionExecutionContextTest`**

Rename the file and retarget it to the new type, dropping the boundary-control tests that no longer have a corresponding method.

```bash
git mv src/test/java/io/codemine/java/richpg/TransactionContextTest.java \
       src/test/java/io/codemine/java/richpg/ConnectionExecutionContextTest.java
```

Then, in `ConnectionExecutionContextTest.java`, apply exactly these edits:

1. Rename the class and update its Javadoc:
   - `public class TransactionContextTest {` → `public class ConnectionExecutionContextTest {`
   - `/** Unit tests for {@link TransactionContext}. */` → `/** Unit tests for {@link ConnectionExecutionContext}. */`
2. Replace every `TransactionContext context = TransactionContext.of(X);` with `ConnectionExecutionContext context = new ConnectionExecutionContext(X);` (occurs in the retained tests below).
3. Replace the null-connection test body:
   - Old: `var thrown = assertThrows(NullPointerException.class, () -> TransactionContext.of(null));`
   - New: `var thrown = assertThrows(NullPointerException.class, () -> new ConnectionExecutionContext(null));`
4. **Delete these test methods entirely** (they exercise boundary-control methods that `ConnectionExecutionContext` does not have): `getAutoCommitDelegatesToConnection`, `setAutoCommitDelegatesToConnection`, `getTransactionIsolationDelegatesToConnection`, `setTransactionIsolationDelegatesToConnection`, `isReadOnlyDelegatesToConnection`, `setReadOnlyDelegatesToConnection`, `commitDelegatesToConnection`, and the no-arg `rollbackDelegatesToConnection` (the method asserting `List.of("rollback")` from `context.rollback()`).
5. **Keep** these test methods (retarget their `TransactionContext.of(...)` per edit #2): `ofRejectsNullConnection` (now the constructor per edit #3), `executeStatementDelegatesToConnection`, `executeBatchEmptyReturnsEmptyList`, `executeBatchRejectsNullIterable`, `executeBatchRejectsNullStatement`, `executeBatchRejectsRowsReturningStatement`, `executeBatchRejectsMismatchedSql`, `executeBatchRejectsNullSql`, `setSavepointDelegatesToConnection`, `rollbackToSavepointDelegatesToConnection` (the `context.rollback(savepoint)` one), `releaseSavepointDelegatesToConnection`.
6. Keep all private helpers unchanged: `recordingConnection`, `throwingSavepoint`, `RecordingHandler`, `FakeStatement`, `FakeUpdateStatement`, `FakeRowsReturningStatement`. If removing the boundary tests leaves the `Connection.TRANSACTION_SERIALIZABLE` import unused, leave the `import java.sql.Connection;` — it is still used by `recordingConnection`.

- [ ] **Step 6: Retarget `ExecutionContextIT`**

In `src/test/java/io/codemine/java/richpg/ExecutionContextIT.java`:
- Line 40: `TransactionContext context = TransactionContext.of(conn);` → `ConnectionExecutionContext context = new ConnectionExecutionContext(conn);`
- Update the class Javadoc reference (lines 17-19): change `covered at the unit level by {@link TransactionContextTest}` to `covered at the unit level by {@link ConnectionExecutionContextTest}`.

- [ ] **Step 7: Retarget `TransactionCompositionTest` mocks**

In `src/test/java/io/codemine/java/richpg/TransactionCompositionTest.java`, the three `firstOf`/`or` tests mock `TransactionContext` but only call `ExecutionContext` methods (`setSavepoint`, `rollback(Savepoint)`). Replace all three occurrences:
- `TransactionContext ctx = mock(TransactionContext.class);` → `ExecutionContext ctx = mock(ExecutionContext.class);`

(Occurs at lines 82, 99, 114.)

- [ ] **Step 8: Update the dependency graph**

In `docs/dependency-graph.mmd`:
- Remove the `TransactionContext` node from the `transaction` subgraph.
- Remove the edges `TransactionExecutor --> TransactionContext`, `Transaction --> TransactionContext`, `TransactionContext --> ExecutionContext`, and `TransactionContext --> Transaction`.
- Add a `ConnectionExecutionContext` node under the `statement` or a fitting group. Suggested placement in the `transaction` subgraph:
  `ConnectionExecutionContext["ConnectionExecutionContext<br/><small>plain ExecutionContext over a Connection</small>"]`
- Add edge: `TransactionExecutor --> ConnectionExecutionContext`.
- Note in the `Transaction` node that bodies run against `ExecutionContext` (the `Transaction --> ExecutionContext` edge already exists via `ExecutionContext`; keep the existing `Transaction --> ExecutionContext` relationship if present, otherwise add `Transaction --> ExecutionContext`).

- [ ] **Step 9: Verify**

Run: `mvn verify`
Expected: BUILD SUCCESS. No references to `TransactionContext` remain (`grep -rn TransactionContext src/` returns nothing).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: replace shallow TransactionContext with ConnectionExecutionContext

TransactionContext was a 12-method interface whose only implementation
delegated 1:1 to Connection. Its boundary-control methods move onto the raw
Connection in TransactionExecutor; its body operations become the new
ConnectionExecutionContext. ExecutionContext (the real seam) is unchanged."
```

---

## Task 2: Flatten StatementExecutor to a static method

`StatementExecutor` holds one field (`telemetry`), has one method, and is instantiated per call (`new StatementExecutor(telemetry).execute(...)`). No state, no second adapter — the instance is ceremony. Make `execute` static.

**Files:**
- Modify: `src/main/java/io/codemine/java/richpg/StatementExecutor.java`
- Modify: `src/main/java/io/codemine/java/richpg/Session.java:87-94`
- Modify: `src/test/java/io/codemine/java/richpg/StatementExecutorTest.java`

**Interfaces:**
- Consumes: `Telemetry`, `ConnectionSupplier`, `io.codemine.java.postgresql.jdbc.Statement`, `RetryStrategy`.
- Produces: `static <R> R StatementExecutor.execute(Telemetry telemetry, Statement<R> statement, int maxAttempts, ConnectionSupplier connectionSupplier, Span parentSpan) throws SQLException`. The `telemetry` argument leads; the remaining parameters keep their order.

- [ ] **Step 1: Make the executor stateless**

In `src/main/java/io/codemine/java/richpg/StatementExecutor.java`, remove the field and constructor and make both methods static. Replace lines 18-47 (from `final class StatementExecutor {` through the `runAttempts` signature) so the class reads:

```java
final class StatementExecutor {

  private StatementExecutor() {}

  static <R> R execute(
      Telemetry telemetry,
      Statement<R> statement,
      int maxAttempts,
      ConnectionSupplier connectionSupplier,
      Span parentSpan)
      throws SQLException {
    Objects.requireNonNull(telemetry, "telemetry");
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(connectionSupplier, "connectionSupplier");

    Telemetry.StatementOperationHandle operation =
        telemetry.startStatementOperation(statement, maxAttempts, parentSpan);
    try (var scope = operation.span().makeCurrent()) {
      return runAttempts(telemetry, statement, maxAttempts, connectionSupplier, operation);
    }
  }

  private static <R> R runAttempts(
      Telemetry telemetry,
      Statement<R> statement,
      int maxAttempts,
      ConnectionSupplier connectionSupplier,
      Telemetry.StatementOperationHandle operation)
      throws SQLException {
```

The body of `runAttempts` (lines 48-79) is unchanged — it already refers to `telemetry`, which is now a parameter instead of a field.

- [ ] **Step 2: Update the `Session` call site**

In `src/main/java/io/codemine/java/richpg/Session.java`, replace the body of `execute(Statement, Span)` (lines 91-93):

```java
    return StatementExecutor.execute(
        telemetry, statement, settings.retryAttempts(), dataSource::getConnection, parentSpan);
```

(Delete the `StatementExecutor executor = new StatementExecutor(telemetry);` line.)

- [ ] **Step 3: Update `StatementExecutorTest` call sites**

In `src/test/java/io/codemine/java/richpg/StatementExecutorTest.java`, replace each `new StatementExecutor(telemetry).execute(...)` with `StatementExecutor.execute(telemetry, ...)`. The five call sites become:
- `StatementExecutor.execute(telemetry, statement, 3, () -> connection, Span.getInvalid())` (was line 61)
- `StatementExecutor.execute(telemetry, statement, 3, supplied::poll, Span.getInvalid())` (was lines 89, 109)
- The two multiline calls (was lines 123, 142): change `new StatementExecutor(telemetry)\n    .execute(statement, 3, ...)` to `StatementExecutor.execute(telemetry, statement, 3, ...)`.

Concretely, the `telemetry` argument moves to the front of the argument list and the `new StatementExecutor(telemetry)` receiver is removed.

- [ ] **Step 4: Verify**

Run: `mvn verify`
Expected: BUILD SUCCESS. `grep -rn "new StatementExecutor" src/` returns nothing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: make StatementExecutor.execute a static method

The executor was stateless apart from telemetry and instantiated per call.
Flatten it to a static method taking telemetry as its first argument."
```

---

## Task 3: Flatten TransactionExecutor to a static method

Same shape and rationale as Task 2, applied to `TransactionExecutor`. (Depends on Task 1's rewrite of `TransactionExecutor`; apply on top of it.)

**Files:**
- Modify: `src/main/java/io/codemine/java/richpg/TransactionExecutor.java`
- Modify: `src/main/java/io/codemine/java/richpg/Session.java:142-146`
- Modify: `src/test/java/io/codemine/java/richpg/TransactionExecutorIT.java`

**Interfaces:**
- Consumes: `Telemetry`, `TransactionSettings`, `Transaction`, `Connection`, `Span`, `ConnectionExecutionContext`, `ClassifiedSqlFailure`.
- Produces: `static <R> R TransactionExecutor.execute(Telemetry telemetry, Transaction<R> transaction, TransactionSettings settings, int maxAttempts, Connection connection, Span parentSpan) throws SQLException`. The `telemetry` argument leads.

- [ ] **Step 1: Make the executor stateless**

In `src/main/java/io/codemine/java/richpg/TransactionExecutor.java` (the Task 1 version):
1. Remove the `private final Telemetry telemetry;` field and the `TransactionExecutor(Telemetry telemetry)` constructor; add a `private TransactionExecutor() {}`.
2. Change `execute` to `static` and add `Telemetry telemetry` as its first parameter:

```java
  static <R> R execute(
      Telemetry telemetry,
      Transaction<R> transaction,
      TransactionSettings settings,
      int maxAttempts,
      Connection connection,
      Span parentSpan)
      throws SQLException {
    Objects.requireNonNull(telemetry, "telemetry");
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(connection, "connection");
```

3. Change `runAttempts` to `static` and add `Telemetry telemetry` as its first parameter; update its call site in `execute` to `runAttempts(telemetry, transaction, maxAttempts, connection, operation)`.
4. `NestedExecutionContext` currently references the enclosing instance's `telemetry`. Because the class is now static-only, make `NestedExecutionContext` a `private static final class` and give it a `Telemetry telemetry` field set from a constructor argument. Update its constructor to `NestedExecutionContext(Telemetry telemetry, ExecutionContext delegate, Span transactionSpan)` and its instantiation in `runAttempts` to `new NestedExecutionContext(telemetry, new ConnectionExecutionContext(connection), operation.span())`.

The resulting `NestedExecutionContext` header:

```java
  private static final class NestedExecutionContext implements ExecutionContext {
    private final Telemetry telemetry;
    private final ExecutionContext delegate;
    private final Span transactionSpan;

    NestedExecutionContext(Telemetry telemetry, ExecutionContext delegate, Span transactionSpan) {
      this.telemetry = telemetry;
      this.delegate = delegate;
      this.transactionSpan = transactionSpan;
    }
```

(The rest of `NestedExecutionContext` — `execute`, `executeBatch`, `traced`, `SqlSupplier`, savepoint methods — is unchanged; it now reads its own `telemetry` field.)

- [ ] **Step 2: Update the `Session` call site**

In `src/main/java/io/codemine/java/richpg/Session.java`, replace the body of the innermost `executeTransaction` (lines 142-146):

```java
    try (Connection connection = dataSource.getConnection()) {
      return TransactionExecutor.execute(
          telemetry, transaction, transactionSettings, settings.retryAttempts(), connection,
          parentSpan);
    }
```

(Delete the `TransactionExecutor executor = new TransactionExecutor(telemetry);` line.)

- [ ] **Step 3: Update `TransactionExecutorIT` call sites**

In `src/test/java/io/codemine/java/richpg/TransactionExecutorIT.java`, replace each `new TransactionExecutor(telemetry).execute(...)` with `TransactionExecutor.execute(telemetry, ...)` — moving `telemetry` to the front of the argument list. Five call sites (was lines 21, 63, 93, 132, 165). For example, `commitsOnSuccess` becomes:

```java
      String result =
          TransactionExecutor.execute(
              telemetry,
              ctx -> "ok",
              TransactionSettings.SERIALIZABLE_WRITE,
              3,
              connection,
              Span.getInvalid());
```

Apply the identical transformation to the other four calls.

- [ ] **Step 4: Verify**

Run: `mvn verify`
Expected: BUILD SUCCESS. `grep -rn "new TransactionExecutor" src/` returns nothing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: make TransactionExecutor.execute a static method

Mirror the StatementExecutor change: the executor was stateless apart from
telemetry and instantiated per call. Flatten to a static method taking
telemetry first; NestedExecutionContext becomes static with its own field."
```

---

## Task 4: Unify Telemetry operation-handle lifecycle via AutoCloseable

`StatementOperationHandle.finish` ends its span; `TransactionOperationHandle.finish` deliberately does not, forcing the executor to remember a second call, `recordDurationAndEnd()`. Two near-identical handles with opposite lifecycle rules is a footgun. Unify: `finish(...)` sets outcome attributes only; `close()` (via `AutoCloseable`) records duration and ends the span. The executor's connection-state restore then sits naturally inside the try-block, before `close()`, preserving the "restore is inside span duration" property.

**Files:**
- Modify: `src/main/java/io/codemine/java/richpg/Telemetry.java`
- Modify: `src/main/java/io/codemine/java/richpg/StatementExecutor.java`
- Modify: `src/main/java/io/codemine/java/richpg/TransactionExecutor.java`

**Interfaces:**
- Consumes: existing `Telemetry` internals (`finishOperationAttributes`, `recordDuration`, `logIfSlow`, `logIfExhausted`, `Outcome`).
- Produces:
  - `Telemetry.StatementOperationHandle implements AutoCloseable`: `Span span()`, `void finish(int attempts, Outcome outcome, Throwable failure)` (sets attributes only), `void close()` (records duration + ends span).
  - `Telemetry.TransactionOperationHandle implements AutoCloseable`: `Span span()`, `void finish(int attempts, Outcome outcome, Throwable failure)` (sets attributes only), `void close()` (records duration + ends span). The old `recordDurationAndEnd()` is removed.

- [ ] **Step 1: Make `StatementOperationHandle` `AutoCloseable`**

In `src/main/java/io/codemine/java/richpg/Telemetry.java`, change the `StatementOperationHandle` class (currently lines 366-397) so `finish` no longer records duration or ends the span, and add `close()`:

```java
  /** A started standalone-statement operation span plus what's needed to finish it. */
  final class StatementOperationHandle implements AutoCloseable {
    private final Span span;
    private final String statementName;
    private final long startNanos = System.nanoTime();

    private StatementOperationHandle(Span span, String statementName) {
      this.span = span;
      this.statementName = statementName;
    }

    Span span() {
      return span;
    }

    void finish(int attempts, Outcome outcome, Throwable failure) {
      String outcomeLabel = outcomeLabel(outcome, OUTCOME_SUCCEEDED);
      finishOperationAttributes(
          span, statementRetries, ATTEMPT_COUNT_STMT, OUTCOME_STMT, attempts, outcomeLabel, failure);
      logIfExhausted(statementName, outcome, attempts, failure);
    }

    @Override
    public void close() {
      Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
      recordDuration(
          duration, Attributes.of(DB_SYSTEM_NAME, DB_SYSTEM, STATEMENT_NAME, statementName));
      logIfSlow(statementName, duration);
      span.end();
    }
  }
```

- [ ] **Step 2: Make `TransactionOperationHandle` `AutoCloseable`**

In the same file, change `TransactionOperationHandle` (currently lines 423-454). `finish` already sets attributes only; rename `recordDurationAndEnd()` to `close()` and implement `AutoCloseable`:

```java
  /**
   * A started transaction operation span plus what's needed to finish it. {@link #finish} records
   * outcome attributes; {@link #close} records the duration and ends the span. The executor restores
   * connection state between the two, inside the try-with-resources block, so the restore stays
   * within the span's duration.
   */
  final class TransactionOperationHandle implements AutoCloseable {
    private final Span span;
    private final long startNanos = System.nanoTime();

    private TransactionOperationHandle(Span span) {
      this.span = span;
    }

    Span span() {
      return span;
    }

    void finish(int attempts, Outcome outcome, Throwable failure) {
      String outcomeLabel = outcomeLabel(outcome, OUTCOME_COMMITTED);
      finishOperationAttributes(
          span, transactionRetries, ATTEMPT_COUNT_TXN, OUTCOME_TXN, attempts, outcomeLabel, failure);
      logIfExhausted("Transaction", outcome, attempts, failure);
    }

    @Override
    public void close() {
      Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
      recordDuration(duration, Attributes.of(DB_SYSTEM_NAME, DB_SYSTEM));
      logIfSlow("transaction", duration);
      span.end();
    }
  }
```

- [ ] **Step 3: Update `StatementExecutor` to use try-with-resources**

In `src/main/java/io/codemine/java/richpg/StatementExecutor.java` (the Task 2 static version), wrap the operation handle so `close()` ends the span. Replace the `execute` body:

```java
    try (Telemetry.StatementOperationHandle operation =
            telemetry.startStatementOperation(statement, maxAttempts, parentSpan);
        var scope = operation.span().makeCurrent()) {
      return runAttempts(telemetry, statement, maxAttempts, connectionSupplier, operation);
    }
```

`runAttempts` already calls `operation.finish(...)` on every terminal path; the `finish` no longer ends the span, and `operation.close()` (from try-with-resources) now does. No other change to `runAttempts`.

- [ ] **Step 4: Update `TransactionExecutor` to use try-with-resources**

In `src/main/java/io/codemine/java/richpg/TransactionExecutor.java` (the Task 3 static version), replace the operation-handle handling in `execute` so `close()` runs after the connection-state restore. The method body becomes:

```java
    try (Telemetry.TransactionOperationHandle operation =
        telemetry.startTransactionOperation(settings, maxAttempts, parentSpan)) {
      boolean originalAutoCommit = connection.getAutoCommit();
      int originalIsolation = connection.getTransactionIsolation();
      boolean originalReadOnly = connection.isReadOnly();

      connection.setAutoCommit(false);
      connection.setTransactionIsolation(settings.isolationLevel().jdbcLevel());
      connection.setReadOnly(settings.readOnly());

      try (var scope = operation.span().makeCurrent()) {
        return runAttempts(telemetry, transaction, maxAttempts, connection, operation);
      } finally {
        try {
          connection.setAutoCommit(originalAutoCommit);
          connection.setTransactionIsolation(originalIsolation);
          connection.setReadOnly(originalReadOnly);
        } catch (SQLException ignoredRestoreFailure) {
          // best-effort restore; the primary outcome already determined what propagates
        }
      }
    }
```

The inner `finally` (state restore) runs before the outer try-with-resources closes `operation`, so `operation.close()` records duration and ends the span after restore — the exact ordering the old `recordDurationAndEnd()`-in-`finally` provided. Remove the old `operation.recordDurationAndEnd();` line (there is no longer a separate call).

- [ ] **Step 5: Verify**

Run: `mvn verify`
Expected: BUILD SUCCESS. The existing `TransactionExecutorIT` assertions on `attempt_count`, `outcome`, span events, and `StatementExecutorTest`/`TelemetryTest` assertions on duration/spans all still pass — they are the safety net proving the lifecycle is preserved. `grep -rn "recordDurationAndEnd" src/` returns nothing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: unify Telemetry operation-handle lifecycle via AutoCloseable

Both operation handles now split finish() (attributes) from close() (records
duration + ends span). Executors use try-with-resources, so the transaction
state-restore stays inside the span duration without a bespoke second call."
```

---

## Task 5: De-duplicate Telemetry span construction

Five start methods (`startStatement`/`startBatch` via `startStatementSpan`, `startStatementOperation`, `startTransactionOperation`, `startHealthCheckSpan`, and `CloseHandle`'s span) repeat the same scaffolding: `spanBuilder(name).setSpanKind(kind).setAttribute(DB_SYSTEM_NAME, DB_SYSTEM).setAttribute(ARTIFACT_NAME, artifactName)` plus the `setParent` guard. Extract one private helper so each caller adds only its distinctive attributes. Pure internal refactor — no interface change.

**Files:**
- Modify: `src/main/java/io/codemine/java/richpg/Telemetry.java`

**Interfaces:**
- Consumes/Produces: no change to any public or package-private method signature. Adds one private helper `SpanBuilder newSpanBuilder(String name, SpanKind kind, Span parentSpan)`.

- [ ] **Step 1: Add the helper**

In `src/main/java/io/codemine/java/richpg/Telemetry.java`, add the import `import io.opentelemetry.api.trace.SpanBuilder;` and a private helper (place it near the other private span helpers, e.g. just above `startStatementSpan`):

```java
  /**
   * Starts a span builder pre-seeded with the attributes present on every rich-pg span (db system,
   * artifact name) and parented to {@code parentSpan} when non-null.
   */
  private SpanBuilder newSpanBuilder(String name, SpanKind kind, Span parentSpan) {
    SpanBuilder builder =
        tracer
            .spanBuilder(name)
            .setSpanKind(kind)
            .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
            .setAttribute(ARTIFACT_NAME, artifactName);
    if (parentSpan != null) {
      builder.setParent(Context.current().with(parentSpan));
    }
    return builder;
  }
```

- [ ] **Step 2: Route `startStatementSpan` through the helper**

Replace the builder construction in `startStatementSpan` (currently lines 253-269) with:

```java
    var builder =
        newSpanBuilder(statementName, SpanKind.CLIENT, parentSpan)
            .setAttribute(DB_QUERY_TEXT, sql)
            .setAttribute(STATEMENT_NAME, statementName)
            .setAttribute(DB_USER, dbUser);
    operationName.ifPresent(v -> builder.setAttribute(DB_OPERATION_NAME, v));
    collectionName.ifPresent(v -> builder.setAttribute(DB_COLLECTION_NAME, v));
    if (batchSize != null) {
      builder.setAttribute(BATCH_SIZE, (long) batchSize);
    }
```

(The `setParent` guard is now inside `newSpanBuilder`; remove the trailing `if (parentSpan != null) { ... }` block from this method. Note `DB_SYSTEM_NAME` and `ARTIFACT_NAME` are now set by the helper — do not set them again here.)

- [ ] **Step 3: Route `startStatementOperation` through the helper**

Replace its builder (currently lines 350-361) with:

```java
    var builder =
        newSpanBuilder(statement.statementName(), SpanKind.CLIENT, parentSpan)
            .setAttribute(DB_QUERY_TEXT, statement.sql())
            .setAttribute(STATEMENT_NAME, statement.statementName())
            .setAttribute(MAX_ATTEMPTS_STMT, (long) maxAttempts);
```

(Remove the trailing `setParent` guard block; `DB_SYSTEM_NAME`/`ARTIFACT_NAME` come from the helper.)

- [ ] **Step 4: Route `startTransactionOperation` through the helper**

Replace its builder (currently lines 402-413) with:

```java
    var builder =
        newSpanBuilder("transaction", SpanKind.INTERNAL, parentSpan)
            .setAttribute(ISOLATION_LEVEL, settings.isolationLevel().name())
            .setAttribute(READ_ONLY, settings.readOnly())
            .setAttribute(MAX_ATTEMPTS_TXN, (long) maxAttempts);
```

(Remove the trailing `setParent` guard block.)

- [ ] **Step 5: Route `startHealthCheckSpan` and `CloseHandle` through the helper**

Replace `startHealthCheckSpan` (currently lines 501-508) with:

```java
  Span startHealthCheckSpan() {
    return newSpanBuilder("healthCheck", SpanKind.CLIENT, null).startSpan();
  }
```

In `CloseHandle.finish` (currently lines 521-528), replace the span builder with:

```java
      Span span =
          newSpanBuilder("session.close", SpanKind.INTERNAL, null)
              .setAttribute(CLOSE_CONNECTIONS_REMAINING, (long) remainingConnections)
              .startSpan();
```

- [ ] **Step 6: Verify**

Run: `mvn verify`
Expected: BUILD SUCCESS. `TelemetryTest` and the `*IT` span assertions (which check `db.system.name`, `pgenie.artifact.name`, and the distinctive per-span attributes) confirm the attribute set is unchanged.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: extract Telemetry.newSpanBuilder to de-duplicate span setup

All span builders share db.system.name, artifact name, and the parent guard.
Extract one helper so each start method adds only its distinctive attributes."
```

---

## Out of scope (deliberately unchanged)

- **`ConnectionSupplier`** — kept as-is. It is a genuine seam with two adapters (`dataSource::getConnection` in production, `supplied::poll` in `StatementExecutorTest`), and it narrows "borrow a connection" to exactly the one method the retry loop needs. No change.
- **Splitting `Telemetry` into multiple classes** — rejected. `Telemetry` is a legitimately deep module; its single `Tracer`/`Meter`/config is shared across span construction, retry counters, and pool-gauge lifecycle. Tasks 4-5 refine its internals without shattering the "one instrumentation surface" cohesion.

---

## Self-Review

- **Coverage:** Task 1 = TransactionContext removal (highest-priority structural win); Tasks 2-3 = flatten the two executors; Task 4 = Telemetry handle-lifecycle unification; Task 5 = Telemetry span de-duplication; ConnectionSupplier explicitly out of scope. All five recommendations from the design discussion are covered.
- **Type consistency:** `TransactionExecutor.execute` / `StatementExecutor.execute` gain `Telemetry telemetry` as their first parameter (Tasks 2-3), and every call site (Session, IT tests) is updated in the same task. `finish(...)` keeps its `(int, Outcome, Throwable)` signature across both handles (Task 4); `recordDurationAndEnd()` is removed and replaced by `close()` everywhere it was called. `ConnectionExecutionContext(Connection)` is the sole new constructor and is referenced only in Task 1's `TransactionExecutor` and the migrated tests.
- **Ordering:** Task 1 rewrites `TransactionExecutor` (delegate wiring); Task 3 makes it static; Task 4 changes its handle usage. Apply in numeric order. Tasks 2 and 4-5 (StatementExecutor, Telemetry) are independent of Task 1 and could run in parallel, but the plan assumes sequential execution.
