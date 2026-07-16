package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Executes a single {@link Statement} outside of a transaction.
 *
 * <p>TODO(Task 5): this is a temporary compiling stub. The retry loop and full Telemetry-driven
 * span/event recording (per design-revision-plan §3.1) are added when this class is fully
 * rewritten.
 */
final class StatementExecutor {

  private final Telemetry telemetry;

  StatementExecutor(Telemetry telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
  }

  <R> R execute(Statement<R> statement, ConnectionSupplier connectionSupplier, Span parentSpan)
      throws SQLException {
    // TODO(Task 5): replace with the retry loop + Telemetry-driven span/event recording.
    try (Connection c = connectionSupplier.get()) {
      return statement.execute(c);
    }
  }
}
