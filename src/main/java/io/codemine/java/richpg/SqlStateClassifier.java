package io.codemine.java.richpg;

import java.sql.SQLException;

/**
 * Classifies a failure's PostgreSQL SQLSTATE into the retry strategy a retry loop should use.
 *
 * <p>{@code 40001} (serialization failure), {@code 40P01} (deadlock detected) and {@code 23505}
 * (unique violation, which PostgreSQL may raise instead of {@code 40001} under {@code SERIALIZABLE}
 * isolation) are retried on the same connection regardless of idempotency, since PostgreSQL
 * guarantees the failing statement's own implicit transaction did not commit. SQLSTATE class {@code
 * 08} (connection exception) is retried on a freshly borrowed connection, but only when the
 * operation is idempotent: the outcome is ambiguous, so retrying a non-idempotent operation could
 * duplicate an effect that already landed. Every other failure is not retried.
 */
final class SqlStateClassifier {

  private SqlStateClassifier() {}

  /** The connection strategy a retry loop should use for its next attempt. */
  enum RetryStrategy {
    /** Retry on the same connection: the failure did not compromise the connection itself. */
    SAME_CONNECTION,
    /** Retry on a freshly borrowed connection: the failed connection may no longer be usable. */
    NEW_CONNECTION,
    /** Do not retry. */
    NO_RETRY
  }

  /**
   * Classifies {@code failure} into the retry strategy a retry loop should use.
   *
   * @param failure the exception to classify; may be null
   * @param idempotent whether the operation being retried is idempotent
   * @return the retry strategy to use for the next attempt
   */
  static RetryStrategy classify(Throwable failure, boolean idempotent) {
    if (failure == null) {
      return RetryStrategy.NO_RETRY;
    }
    SQLException sqlException = extractSqlException(failure);
    if (sqlException == null) {
      return RetryStrategy.NO_RETRY;
    }
    String state = sqlException.getSQLState();
    if (state == null) {
      return RetryStrategy.NO_RETRY;
    }
    if (state.equals("40001") || state.equals("40P01") || state.equals("23505")) {
      return RetryStrategy.SAME_CONNECTION;
    }
    if (state.startsWith("08") && idempotent) {
      return RetryStrategy.NEW_CONNECTION;
    }
    return RetryStrategy.NO_RETRY;
  }

  /**
   * Returns true if {@code failure}'s SQLSTATE marks a transaction-wide failure that a savepoint
   * rollback cannot heal ({@code 40001} serialization failure, {@code 40P01} deadlock detected) —
   * these must propagate to the transaction-level retry loop instead of being absorbed by {@link
   * io.codemine.java.richpg.Transaction#or}.
   */
  static boolean isTransactionWide(Throwable failure) {
    if (failure == null) {
      return false;
    }
    if (!(failure instanceof SQLException sqlException)) {
      return false;
    }
    String state = sqlException.getSQLState();
    return state != null && (state.equals("40001") || state.equals("40P01"));
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
