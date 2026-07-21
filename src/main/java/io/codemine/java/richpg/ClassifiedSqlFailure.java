package io.codemine.java.richpg;

import java.sql.SQLException;

/**
 * Wraps a failure with its extracted PostgreSQL SQLSTATE (if any), exposing the retry decisions the
 * statement and transaction retry loops need to make.
 *
 * <p>{@code 40001} (serialization failure) and {@code 40P01} (deadlock detected) are retried on the
 * same connection regardless of idempotency, since PostgreSQL guarantees the failing statement's
 * own implicit transaction did not commit. The transaction retry loop additionally retries {@code
 * 23505} (unique violation, which PostgreSQL may raise instead of {@code 40001} under {@code
 * SERIALIZABLE} isolation) via {@link #isTransactionRetryable}: retrying it at statement
 * granularity would be near-always futile, since the statement loop has no savepoint to fall back
 * to and would simply repeat the same conflicting write. SQLSTATE class {@code 08} (connection
 * exception) is retried on a freshly borrowed connection, but only when the operation is
 * idempotent: the outcome is ambiguous, so retrying a non-idempotent operation could duplicate an
 * effect that already landed. Every other failure is not retried.
 */
final class ClassifiedSqlFailure {

  private static final String SERIALIZATION_FAILURE = "40001";
  private static final String DEADLOCK_DETECTED = "40P01";
  private static final String UNIQUE_VIOLATION = "23505";
  private static final String CONNECTION_EXCEPTION_CLASS = "08";

  private final String sqlState;

  ClassifiedSqlFailure(Throwable failure) {
    this.sqlState = extractSqlState(failure);
  }

  /**
   * The retry strategy the statement retry loop should use for its next attempt.
   *
   * @param idempotent whether the operation being retried is idempotent
   * @return the retry strategy to use for the next attempt
   */
  RetryStrategy toRetryStrategy(boolean idempotent) {
    if (sqlState == null) {
      return RetryStrategy.NO_RETRY;
    }
    if (sqlState.equals(SERIALIZATION_FAILURE) || sqlState.equals(DEADLOCK_DETECTED)) {
      return RetryStrategy.SAME_CONNECTION;
    }
    if (sqlState.startsWith(CONNECTION_EXCEPTION_CLASS) && idempotent) {
      return RetryStrategy.NEW_CONNECTION;
    }
    return RetryStrategy.NO_RETRY;
  }

  /**
   * Returns true if the transaction retry loop should retry this failure on the same connection:
   * serialization failure ({@code 40001}), deadlock detected ({@code 40P01}), or unique violation
   * ({@code 23505}).
   */
  boolean isTransactionRetryable() {
    return sqlState != null
        && (sqlState.equals(SERIALIZATION_FAILURE)
            || sqlState.equals(DEADLOCK_DETECTED)
            || sqlState.equals(UNIQUE_VIOLATION));
  }

  /**
   * Returns true if this failure's SQLSTATE marks a transaction-wide failure that a savepoint
   * rollback cannot heal ({@code 40001} serialization failure, {@code 40P01} deadlock detected)
   * &mdash; these must propagate to the transaction-level retry loop instead of being absorbed by
   * {@link io.codemine.java.richpg.Transaction#or}.
   */
  boolean isTransactionWide() {
    return sqlState != null
        && (sqlState.equals(SERIALIZATION_FAILURE) || sqlState.equals(DEADLOCK_DETECTED));
  }

  /**
   * The extracted SQLSTATE, or {@code null} if {@code failure} carries no {@link SQLException}
   * (directly or as its cause).
   */
  String sqlState() {
    return sqlState;
  }

  private static String extractSqlState(Throwable failure) {
    SQLException sqlException = extractSqlException(failure);
    return sqlException == null ? null : sqlException.getSQLState();
  }

  private static SQLException extractSqlException(Throwable t) {
    if (t instanceof SQLException sqlException) {
      return sqlException;
    }
    Throwable cause = t.getCause();
    if (cause instanceof SQLException sqlException) {
      return sqlException;
    }
    return null;
  }
}
