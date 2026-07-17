package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Objects;

/**
 * A plain {@link ExecutionContext} backed directly by a JDBC {@link Connection}.
 *
 * <p>Executes statements and manages savepoints straight against the connection. It holds no
 * transaction-boundary control (commit/rollback, auto-commit, isolation level, read-only): those
 * are performed by {@link Session} on the connection itself, so transaction bodies — which only
 * ever see an {@link ExecutionContext} — cannot reach them.
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
