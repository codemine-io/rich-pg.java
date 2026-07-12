package io.codemine.java.reachpg.observability;

import java.sql.SQLException;

/**
 * A database operation that may throw {@link SQLException}.
 *
 * <p>Used by {@link StatementObservability} to wrap an operation with spans, metrics and slow-query
 * logging without needing to know the operation's concrete type.
 *
 * @param <R> the type returned by the operation
 */
@FunctionalInterface
public interface SqlOperation<R> {

  /**
   * Executes the operation.
   *
   * @return the operation result
   * @throws SQLException if a database access error occurs
   */
  R execute() throws SQLException;
}
