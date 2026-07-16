package io.codemine.java.richpg;

import java.util.Objects;

/**
 * Configuration for running a {@link Transaction}.
 *
 * @param isolationLevel the isolation level to apply
 * @param readOnly whether to mark the transaction read-only
 */
public record TransactionSettings(IsolationLevel isolationLevel, boolean readOnly) {

  /** Validates the record's components. */
  public TransactionSettings {
    Objects.requireNonNull(isolationLevel, "isolationLevel");
  }

  /** Read-committed isolation, read-only. */
  public static final TransactionSettings READ_COMMITTED_READ =
      new TransactionSettings(IsolationLevel.READ_COMMITTED, true);

  /** Read-committed isolation, read-write. */
  public static final TransactionSettings READ_COMMITTED_WRITE =
      new TransactionSettings(IsolationLevel.READ_COMMITTED, false);

  /** Repeatable-read isolation, read-only. */
  public static final TransactionSettings REPEATABLE_READ_READ =
      new TransactionSettings(IsolationLevel.REPEATABLE_READ, true);

  /** Repeatable-read isolation, read-write. */
  public static final TransactionSettings REPEATABLE_READ_WRITE =
      new TransactionSettings(IsolationLevel.REPEATABLE_READ, false);

  /** Serializable isolation, read-only. */
  public static final TransactionSettings SERIALIZABLE_READ =
      new TransactionSettings(IsolationLevel.SERIALIZABLE, true);

  /** Serializable isolation, read-write. */
  public static final TransactionSettings SERIALIZABLE_WRITE =
      new TransactionSettings(IsolationLevel.SERIALIZABLE, false);

  /**
   * Returns a copy of these settings with the given isolation level.
   *
   * @param level the isolation level to apply
   * @return a new {@code TransactionSettings}
   */
  public TransactionSettings withIsolationLevel(IsolationLevel level) {
    Objects.requireNonNull(level, "level");
    return new TransactionSettings(level, readOnly);
  }

  /**
   * Returns a copy of these settings with the given read-only flag.
   *
   * @param readOnly whether the transaction should be read-only
   * @return a new {@code TransactionSettings}
   */
  public TransactionSettings withReadOnly(boolean readOnly) {
    return new TransactionSettings(isolationLevel, readOnly);
  }
}
