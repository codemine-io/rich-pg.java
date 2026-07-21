package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
  private final StatementHealthTracker statementHealthTracker;

  ConnectionExecutionContext(Connection connection, StatementHealthTracker statementHealthTracker) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.statementHealthTracker =
        Objects.requireNonNull(statementHealthTracker, "statementHealthTracker");
  }

  /**
   * Executes {@code statement}, inlining {@link Statement#execute}'s body so the SQL-execution step
   * and the decode step can be classified separately for {@link Session#healthCheck}. See ADR 0001.
   * Duplicated (rather than shared) with {@link Session#execute}'s own inlining, per the ADR's
   * decision to accept this duplication for now.
   */
  @Override
  public <R> R execute(Statement<R> statement) throws SQLException {
    Class<?> statementClass = statement.getClass();
    try (PreparedStatement ps = connection.prepareStatement(statement.sql())) {
      R result;
      if (statement.returnsRows()) {
        try {
          statement.bindParams(ps);
          ps.execute();
        } catch (SQLException executionFailure) {
          if (IntegrationalDriftClassifier.isExecutionDrift(executionFailure)) {
            statementHealthTracker.markBroken(statementClass);
          }
          throw executionFailure;
        }
        try (ResultSet rs = ps.getResultSet()) {
          result = statement.decodeResultSet(rs);
        } catch (RuntimeException | SQLException decodeFailure) {
          statementHealthTracker.markBroken(statementClass);
          throw decodeFailure;
        }
      } else {
        long affectedRows;
        try {
          statement.bindParams(ps);
          affectedRows = ps.executeUpdate();
        } catch (SQLException executionFailure) {
          if (IntegrationalDriftClassifier.isExecutionDrift(executionFailure)) {
            statementHealthTracker.markBroken(statementClass);
          }
          throw executionFailure;
        }
        try {
          result = statement.decodeAffectedRows(affectedRows);
        } catch (RuntimeException | SQLException decodeFailure) {
          statementHealthTracker.markBroken(statementClass);
          throw decodeFailure;
        }
      }
      statementHealthTracker.markHealthy(statementClass);
      return result;
    }
  }

  @Override
  public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) throws SQLException {
    return new StatementBatch<>(statements).execute(connection, statementHealthTracker);
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
