package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;

/**
 * The operations available to code running inside a transaction body. This is the context passed to
 * {@link Transaction#run}: it can execute statements and manage savepoints, but it cannot directly
 * commit, roll back, or change transaction-boundary state such as auto-commit or isolation level.
 * Every member is abstract by design: implementors — including instrumentation wrappers that
 * intercept only {@link #execute(Statement)} — must explicitly provide each behavior rather than
 * inherit a {@link java.sql.Connection}-backed default.
 */
public interface ExecutionContext {

  /**
   * Executes {@code statement}.
   *
   * @param statement the statement to execute
   * @param <R> the statement's result type
   * @return the decoded statement result
   * @throws SQLException if a database access error occurs while executing the statement
   */
  <R> R execute(Statement<R> statement) throws SQLException;

  /**
   * Executes {@code statements} as a single JDBC batch.
   *
   * <p>All statements must use the same SQL text and must not return rows. Violations cause an
   * {@link IllegalArgumentException}, which is not caught by {@link Transaction}'s retry loop.
   *
   * @param statements the statements to execute in batch
   * @param <R> the statement result type
   * @return the decoded results, in the same order as the input statements
   * @throws SQLException if a database access error occurs during execution
   * @throws IllegalArgumentException if the statements are null, contain a null element, return
   *     rows, or use different SQL text
   */
  <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) throws SQLException;

  /**
   * Creates a savepoint in the current transaction.
   *
   * @return the new savepoint
   * @throws SQLException if a database access error occurs
   */
  Savepoint setSavepoint() throws SQLException;

  /**
   * Rolls back to {@code savepoint}.
   *
   * @param savepoint the savepoint to roll back to
   * @throws SQLException if a database access error occurs
   */
  void rollback(Savepoint savepoint) throws SQLException;

  /**
   * Releases {@code savepoint}.
   *
   * @param savepoint the savepoint to release
   * @throws SQLException if a database access error occurs
   */
  void releaseSavepoint(Savepoint savepoint) throws SQLException;
}
