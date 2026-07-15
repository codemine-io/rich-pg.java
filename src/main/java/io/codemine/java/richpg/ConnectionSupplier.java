package io.codemine.java.richpg;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies a JDBC connection on demand, e.g. by borrowing one from a pool.
 *
 * <p>Used by {@link StatementExecutor} so its retry loop can borrow a fresh connection per attempt
 * without depending on any particular pool implementation.
 */
@FunctionalInterface
interface ConnectionSupplier {

  /**
   * Returns a connection, e.g. freshly borrowed from a pool.
   *
   * @return a JDBC connection
   * @throws SQLException if a database access error occurs while obtaining the connection
   */
  Connection get() throws SQLException;
}
