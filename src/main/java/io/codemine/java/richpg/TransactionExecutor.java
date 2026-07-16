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
 * counting and transaction-level telemetry per design-revision-plan §1.4/§3.1.
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

    Span operationSpan = telemetry.startTransactionOperation(settings, maxAttempts, parentSpan);
    TransactionContext ctx = TransactionContext.of(connection);
    boolean originalAutoCommit = ctx.getAutoCommit();
    int originalIsolation = ctx.getTransactionIsolation();
    boolean originalReadOnly = ctx.isReadOnly();

    ctx.setAutoCommit(false);
    ctx.setTransactionIsolation(settings.isolationLevel().jdbcLevel());
    ctx.setReadOnly(settings.readOnly());

    try (var scope = operationSpan.makeCurrent()) {
      return runAttempts(transaction, maxAttempts, ctx, operationSpan);
    } finally {
      try {
        ctx.setAutoCommit(originalAutoCommit);
        ctx.setTransactionIsolation(originalIsolation);
        ctx.setReadOnly(originalReadOnly);
      } catch (SQLException ignoredRestoreFailure) {
        // best-effort restore; the primary outcome (success or the original failure) already
        // determined what propagates out of runAttempts
      }
      operationSpan.end();
    }
  }

  private <R> R runAttempts(
      Transaction<R> transaction, int maxAttempts, TransactionContext ctx, Span operationSpan)
      throws SQLException {
    ExecutionContext instrumentedContext = new NestedExecutionContext(ctx, operationSpan);
    for (int attempt = 1; ; attempt++) {
      long attemptStart = System.nanoTime();
      try {
        R result = transaction.run(instrumentedContext);
        ctx.commit();
        telemetry.finishTransactionOperation(operationSpan, attempt, true, false, null);
        return result;
      } catch (Exception e) {
        try {
          ctx.rollback();
        } catch (SQLException suppressed) {
          e.addSuppressed(suppressed);
        }
        Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStart);
        boolean retryable =
            SqlStateClassifier.classify(e, false)
                == SqlStateClassifier.RetryStrategy.SAME_CONNECTION;
        if (!retryable || attempt >= maxAttempts) {
          telemetry.finishTransactionOperation(operationSpan, attempt, false, retryable, e);
          if (e instanceof SQLException sqlException) {
            throw sqlException;
          }
          throw new SQLException("Transaction failed", e);
        }
        telemetry.recordAttemptFailed(operationSpan, attempt, e, attemptDuration);
      }
    }
  }

  /**
   * Executes statements directly against the connection, one single-attempt CLIENT span each, no
   * statement-level retry.
   */
  private final class NestedExecutionContext implements ExecutionContext {
    private final TransactionContext delegate;
    private final Span transactionSpan;

    NestedExecutionContext(TransactionContext delegate, Span transactionSpan) {
      this.delegate = delegate;
      this.transactionSpan = transactionSpan;
    }

    @Override
    public <R> R execute(Statement<R> statement) throws SQLException {
      var handle = telemetry.startNestedStatement(statement, transactionSpan);
      try {
        R result = delegate.execute(statement);
        handle.succeeded();
        return result;
      } catch (SQLException e) {
        handle.failed(e);
        throw e;
      }
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
      var handle = telemetry.startNestedBatch(batch, list.get(0), transactionSpan);
      try {
        List<R> result = delegate.executeBatch(list);
        handle.succeeded();
        return result;
      } catch (SQLException e) {
        handle.failed(e);
        throw e;
      }
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
