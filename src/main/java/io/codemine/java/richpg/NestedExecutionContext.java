package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.StatementBatch;
import io.opentelemetry.api.trace.Span;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes statements against a delegate {@link ExecutionContext}, wrapping each in one
 * single-attempt CLIENT span; no statement-level retry.
 */
final class NestedExecutionContext implements ExecutionContext {
  private final Telemetry telemetry;
  private final ExecutionContext delegate;
  private final Span transactionSpan;

  NestedExecutionContext(Telemetry telemetry, ExecutionContext delegate, Span transactionSpan) {
    this.telemetry = telemetry;
    this.delegate = delegate;
    this.transactionSpan = transactionSpan;
  }

  @Override
  public <R> R execute(Statement<R> statement) throws SQLException {
    return traced(
        telemetry.startStatement(statement, transactionSpan), () -> delegate.execute(statement));
  }

  @Override
  public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) throws SQLException {
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
