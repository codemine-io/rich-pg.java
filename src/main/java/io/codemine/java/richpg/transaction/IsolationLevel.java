package io.codemine.java.richpg.transaction;

import java.sql.Connection;

/** Transaction isolation levels supported by PostgreSQL. */
public enum IsolationLevel {
  READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
  REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
  SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

  private final int jdbcLevel;

  IsolationLevel(int jdbcLevel) {
    this.jdbcLevel = jdbcLevel;
  }

  /**
   * The corresponding {@link Connection} isolation level constant.
   *
   * @return the JDBC isolation level constant
   */
  public int jdbcLevel() {
    return jdbcLevel;
  }
}
