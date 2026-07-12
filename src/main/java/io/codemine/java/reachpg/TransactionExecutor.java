package io.codemine.java.reachpg;

import io.codemine.java.postgresql.jdbc.Transaction;
import io.codemine.java.postgresql.jdbc.TransactionSettings;
import io.codemine.java.reachpg.observability.TransactionObservability;
import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Executes {@link Transaction} instances with OpenTelemetry tracing, retry counting and outcome
 * classification.
 *
 * <p>The executor delegates all observability work to {@link TransactionObservability}. It emits a
 * single INTERNAL {@code "transaction"} span that parents all statement spans created by the
 * transaction body, reports retries via the {@code pgenie.transaction.retries} counter, and
 * annotates the span with transaction settings, total attempts and final outcome.
 */
public final class TransactionExecutor {

  private final TransactionObservability observability;

  /**
   * Creates a new transaction executor.
   *
   * @param observability the observability helper used to create and record transaction
   *     observations
   * @throws NullPointerException if {@code observability} is null
   */
  public TransactionExecutor(TransactionObservability observability) {
    this.observability = Objects.requireNonNull(observability, "observability");
  }

  /**
   * Executes the supplied transaction with the given settings and connection.
   *
   * <p>A transaction span is started as a child of {@code parentSpan} (or the current span if
   * {@code parentSpan} is {@code null}). Statement execution inside the transaction body is routed
   * through {@link StatementExecutor} so that statement spans are nested under the transaction
   * span.
   *
   * @param transaction the transaction to execute
   * @param settings the transaction settings
   * @param connection the JDBC connection to use
   * @param parentSpan the parent span, or {@code null} to use the current span
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R execute(
      Transaction<R> transaction,
      TransactionSettings settings,
      Connection connection,
      Span parentSpan)
      throws SQLException {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(connection, "connection");

    var observation = observability.observe(settings, connection, parentSpan);
    try {
      try (var scope = observation.span().makeCurrent()) {
        R result = transaction.executeOn(observation, settings);
        observation.markCommitted();
        return result;
      }
    } catch (Throwable t) {
      observation.markFailed(t);
      throw t;
    } finally {
      observation.close();
    }
  }
}
