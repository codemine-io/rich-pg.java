package io.codemine.java.richpg;

/**
 * Configuration for {@link Session#executeRetryable}.
 *
 * @param maxAttempts the maximum number of attempts, including the first; at least 1
 */
public record StatementSettings(int maxAttempts) {

  /**
   * Validates the record's components.
   *
   * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
   */
  public StatementSettings {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
  }

  /** Default settings, using the same default attempt count as {@code TransactionSettings}. */
  public static final StatementSettings DEFAULT = new StatementSettings(7);

  /**
   * Returns a copy of these settings with the given maximum number of attempts.
   *
   * @param maxAttempts the maximum number of attempts, including the first; at least 1
   * @return a new {@code StatementSettings}
   * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
   */
  public StatementSettings withMaxAttempts(int maxAttempts) {
    return new StatementSettings(maxAttempts);
  }
}
