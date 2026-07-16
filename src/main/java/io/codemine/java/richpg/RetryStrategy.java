package io.codemine.java.richpg;

/** The connection strategy a retry loop should use for its next attempt. */
enum RetryStrategy {
  /** Retry on the same connection: the failure did not compromise the connection itself. */
  SAME_CONNECTION,
  /** Retry on a freshly borrowed connection: the failed connection may no longer be usable. */
  NEW_CONNECTION,
  /** Do not retry. */
  NO_RETRY;

  /**
   * Classifies {@code failure} into the retry strategy the statement retry loop should use.
   *
   * @param failure the exception to classify
   * @param idempotent whether the operation being retried is idempotent
   * @return the retry strategy to use for the next attempt
   */
  static RetryStrategy classify(Throwable failure, boolean idempotent) {
    return new ClassifiedSqlFailure(failure).toRetryStrategy(idempotent);
  }
}
