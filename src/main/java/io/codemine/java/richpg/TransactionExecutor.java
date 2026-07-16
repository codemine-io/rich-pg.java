package io.codemine.java.richpg;

import io.opentelemetry.api.trace.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Executes {@link Transaction} instances against a connection.
 *
 * <p>TODO(Task 6): this is a temporary compiling stub. The retry loop and full Telemetry-driven
 * span/event recording (per design-revision-plan §1.4/§3.1) are added when this class is fully
 * rewritten.
 */
final class TransactionExecutor {

  private final Telemetry telemetry;

  TransactionExecutor(Telemetry telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
  }

  <R> R execute(
      Transaction<R> transaction,
      TransactionSettings settings,
      Connection connection,
      Span parentSpan)
      throws SQLException {
    // TODO(Task 6): replace with the retry loop + Telemetry-driven span/event recording.
    TransactionContext ctx = TransactionContext.of(connection);
    ctx.setAutoCommit(false);
    ctx.setTransactionIsolation(settings.isolationLevel().jdbcLevel());
    ctx.setReadOnly(settings.readOnly());
    try {
      R result = transaction.run(ctx);
      ctx.commit();
      return result;
    } catch (Exception e) {
      ctx.rollback();
      throw e;
    }
  }
}
