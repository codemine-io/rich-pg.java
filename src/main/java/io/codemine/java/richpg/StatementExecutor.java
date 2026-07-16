package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Executes a single {@link Statement} outside of a transaction, always retrying per {@link
 * SqlStateClassifier}.
 *
 * <p>Per design §2.2/§2.3, there is no non-retrying entry point: safety is guaranteed by the
 * classifier only retrying {@code 08}-class connection failures when {@link Statement#idempotent()}
 * is true, and always retrying {@code 40001}/{@code 40P01}/{@code 23505} on the same connection
 * regardless of idempotency.
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

    Span operationSpan = telemetry.startStatementOperation(statement, maxAttempts, parentSpan);
    try (var scope = operationSpan.makeCurrent()) {
      return runAttempts(statement, maxAttempts, connectionSupplier, operationSpan);
    }
  }

  private <R> R runAttempts(
      Statement<R> statement,
      int maxAttempts,
      ConnectionSupplier connectionSupplier,
      Span operationSpan)
      throws SQLException {
    Connection connection = connectionSupplier.get();
    try {
      for (int attempt = 1; ; attempt++) {
        long attemptStart = System.nanoTime();
        try {
          R result = statement.execute(connection);
          telemetry.finishStatementOperation(operationSpan, attempt, true, false, null);
          return result;
        } catch (SQLException failure) {
          Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStart);
          SqlStateClassifier.RetryStrategy strategy =
              SqlStateClassifier.classify(failure, statement.idempotent());
          boolean retryable = strategy != SqlStateClassifier.RetryStrategy.NO_RETRY;
          if (!retryable || attempt >= maxAttempts) {
            telemetry.finishStatementOperation(operationSpan, attempt, false, retryable, failure);
            throw failure;
          }
          telemetry.recordAttemptFailed(operationSpan, attempt, failure, attemptDuration);
          if (strategy == SqlStateClassifier.RetryStrategy.NEW_CONNECTION) {
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
