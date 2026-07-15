package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.richpg.observability.StatementRetryObservability;
import io.codemine.java.richpg.observability.StatementRetryObservability.ConnectionStrategy;
import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Executes a single {@link Statement} outside of a transaction, retrying it when the statement is
 * declared {@link Statement#idempotent() idempotent} and the failure is safe to retry.
 *
 * <p>Delegates all observability work to {@link StatementRetryObservability}, which also classifies
 * each failure into the {@link ConnectionStrategy} used to obtain the connection for the next
 * attempt: a fresh connection for ambiguous-outcome connection failures on idempotent statements,
 * or the same connection for failures PostgreSQL guarantees did not commit.
 */
final class StatementExecutor {

  private final StatementRetryObservability observability;

  /**
   * Creates a new statement executor.
   *
   * @param observability the observability helper used to create and record retry observations
   * @throws NullPointerException if {@code observability} is null
   */
  StatementExecutor(StatementRetryObservability observability) {
    this.observability = Objects.requireNonNull(observability, "observability");
  }

  /**
   * Executes {@code statement}, retrying on a retryable failure per {@code settings}.
   *
   * @param statement the statement to execute
   * @param settings the statement settings
   * @param connectionSupplier supplies the connection for the first attempt, and for each attempt
   *     that follows a failure classified as {@link ConnectionStrategy#NEW_CONNECTION}
   * @param parentSpan the parent span, or {@code null} to use the current span
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  <R> R execute(
      Statement<R> statement,
      StatementSettings settings,
      ConnectionSupplier connectionSupplier,
      Span parentSpan)
      throws SQLException {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(connectionSupplier, "connectionSupplier");

    var observation = observability.observe(settings, parentSpan);
    try (var scope = observation.span().makeCurrent()) {
      return runAttempts(statement, settings, connectionSupplier, observation);
    } finally {
      observation.close();
    }
  }

  private <R> R runAttempts(
      Statement<R> statement,
      StatementSettings settings,
      ConnectionSupplier connectionSupplier,
      StatementRetryObservability.StatementRetryObservation observation)
      throws SQLException {
    Connection connection = connectionSupplier.get();
    try {
      for (int attempt = 1; ; attempt++) {
        Connection attemptConnection = connection;
        try {
          R result =
              observation
                  .forAttempt(statement)
                  .execute(() -> statement.executeOn(attemptConnection));
          observation.markSucceeded(attempt);
          return result;
        } catch (SQLException failure) {
          ConnectionStrategy strategy =
              StatementRetryObservability.classify(failure, statement.idempotent());
          boolean retryable = strategy != ConnectionStrategy.NO_RETRY;
          if (!retryable || attempt >= settings.maxAttempts()) {
            observation.markFailed(failure, attempt, retryable);
            throw failure;
          }
          if (strategy == ConnectionStrategy.NEW_CONNECTION) {
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
