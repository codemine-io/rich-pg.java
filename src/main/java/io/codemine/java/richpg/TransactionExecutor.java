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
