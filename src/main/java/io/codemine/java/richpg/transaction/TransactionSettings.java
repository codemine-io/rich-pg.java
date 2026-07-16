package io.codemine.java.richpg.transaction;

import java.util.Objects;

/**
 * Configuration for {@link Transaction#execute(TransactionContext, TransactionSettings)}.
 *
 * @param isolationLevel the isolation level to apply
 * @param readOnly whether to mark the transaction read-only
 * @param maxAttempts the maximum number of attempts, including the first; at least 1. Beyond the
 *     first, attempts are retried only when the failing statement's SQLSTATE is PostgreSQL's {@code
 *     serialization_failure} (40001), {@code deadlock_detected} (40P01), or {@code
 *     unique_violation} (23505) — the latter because, under {@code SERIALIZABLE} isolation,
 *     PostgreSQL may report a genuine serialization conflict as a unique-constraint violation
 *     instead of a serialization failure. This does not substitute for {@code INSERT ... ON
 *     CONFLICT} when the transaction's own intent is an upsert.
 */
public record TransactionSettings(
    IsolationLevel isolationLevel, boolean readOnly, int maxAttempts) {

  /**
   * Validates the record's components.
   *
   * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
   */
  public TransactionSettings {
    Objects.requireNonNull(isolationLevel, "isolationLevel");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
  }

  /** Read-committed isolation, read-only, using the default number of retry attempts. */
  public static final TransactionSettings READ_COMMITTED_READ =
      new TransactionSettings(IsolationLevel.READ_COMMITTED, true, 7);

  /** Read-committed isolation, read-write, using the default number of retry attempts. */
  public static final TransactionSettings READ_COMMITTED_WRITE =
      new TransactionSettings(IsolationLevel.READ_COMMITTED, false, 7);

  /** Repeatable-read isolation, read-only, using the default number of retry attempts. */
  public static final TransactionSettings REPEATABLE_READ_READ =
      new TransactionSettings(IsolationLevel.REPEATABLE_READ, true, 7);

  /** Repeatable-read isolation, read-write, using the default number of retry attempts. */
  public static final TransactionSettings REPEATABLE_READ_WRITE =
      new TransactionSettings(IsolationLevel.REPEATABLE_READ, false, 7);

  /** Serializable isolation, read-only, using the default number of retry attempts. */
  public static final TransactionSettings SERIALIZABLE_READ =
      new TransactionSettings(IsolationLevel.SERIALIZABLE, true, 7);

  /** Serializable isolation, read-write, using the default number of retry attempts. */
  public static final TransactionSettings SERIALIZABLE_WRITE =
      new TransactionSettings(IsolationLevel.SERIALIZABLE, false, 7);

  /**
   * Returns a copy of these settings with the given isolation level.
   *
   * @param level the isolation level to apply
   * @return a new {@code TransactionSettings}
   */
  public TransactionSettings withIsolationLevel(IsolationLevel level) {
    Objects.requireNonNull(level, "level");
    return new TransactionSettings(level, readOnly, maxAttempts);
  }

  /**
   * Returns a copy of these settings with the given read-only flag.
   *
   * @param readOnly whether the transaction should be read-only
   * @return a new {@code TransactionSettings}
   */
  public TransactionSettings withReadOnly(boolean readOnly) {
    return new TransactionSettings(isolationLevel, readOnly, maxAttempts);
  }

  /**
   * Returns a copy of these settings with the given maximum number of attempts.
   *
   * @param maxAttempts the maximum number of attempts, including the first; at least 1
   * @return a new {@code TransactionSettings}
   * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
   */
  public TransactionSettings withMaxAttempts(int maxAttempts) {
    return new TransactionSettings(isolationLevel, readOnly, maxAttempts);
  }
}
