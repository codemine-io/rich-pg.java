package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Executes a single {@link Statement} outside of a transaction, always retrying per {@link
 * RetryStrategy}.
 *
 * <p>There is no non-retrying entry point: safety is guaranteed by only retrying {@code 08}-class
 * connection failures when {@link Statement#idempotent()} is true, and always retrying {@code
 * 40001}/{@code 40P01} on the same connection regardless of idempotency.
 */
final class StatementExecutor {

  private final Telemetry telemetry;

  StatementExecutor(Telemetry telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
  }

  <R> R execute(
      Statement<R> statement,
      int maxAttempts,
      ConnectionSupplier connectionSupplier,
      Span parentSpan)
      throws SQLException {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(connectionSupplier, "connectionSupplier");

    Telemetry.StatementOperationHandle operation =
        telemetry.startStatementOperation(statement, maxAttempts, parentSpan);
    try (var scope = operation.span().makeCurrent()) {
      return runAttempts(statement, maxAttempts, connectionSupplier, operation);
    }
  }

  private <R> R runAttempts(
      Statement<R> statement,
      int maxAttempts,
      ConnectionSupplier connectionSupplier,
      Telemetry.StatementOperationHandle operation)
      throws SQLException {
    Connection connection = connectionSupplier.get();
    try {
      for (int attempt = 1; ; attempt++) {
        long attemptStart = System.nanoTime();
        try {
          R result = statement.execute(connection);
          operation.finish(attempt, Telemetry.Outcome.SUCCEEDED, null);
          return result;
        } catch (SQLException failure) {
          Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStart);
          RetryStrategy strategy = RetryStrategy.classify(failure, statement.idempotent());
          boolean retryable = strategy != RetryStrategy.NO_RETRY;
          if (!retryable || attempt >= maxAttempts) {
            operation.finish(
                attempt,
                retryable
                    ? Telemetry.Outcome.RETRIES_EXHAUSTED
                    : Telemetry.Outcome.NON_RETRYABLE_FAILURE,
                failure);
            throw failure;
          }
          telemetry.recordAttemptFailed(operation.span(), attempt, failure, attemptDuration);
          if (strategy == RetryStrategy.NEW_CONNECTION) {
            connection.close();
            connection = connectionSupplier.get();
          }
        }
      }
    } finally {
      connection.close();
    }
  }
}
