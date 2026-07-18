package io.codemine.java.richpg;

import java.util.Objects;

/**
 * Configuration for running a {@link Transaction}.
 *
 * @param isolationLevel the isolation level to apply
 * @param readOnly whether to mark the transaction read-only
 */
public record TransactionMode(IsolationLevel isolationLevel, boolean readOnly) {

  /** Validates the record's components. */
  public TransactionMode {
    Objects.requireNonNull(isolationLevel, "isolationLevel");
  }

  /** Read-committed isolation, read-only. */
  public static final TransactionMode READ_COMMITTED_READ =
      new TransactionMode(IsolationLevel.READ_COMMITTED, true);

  /** Read-committed isolation, read-write. */
  public static final TransactionMode READ_COMMITTED_WRITE =
      new TransactionMode(IsolationLevel.READ_COMMITTED, false);

  /** Repeatable-read isolation, read-only. */
  public static final TransactionMode REPEATABLE_READ_READ =
      new TransactionMode(IsolationLevel.REPEATABLE_READ, true);

  /** Repeatable-read isolation, read-write. */
  public static final TransactionMode REPEATABLE_READ_WRITE =
      new TransactionMode(IsolationLevel.REPEATABLE_READ, false);

  /** Serializable isolation, read-only. */
  public static final TransactionMode SERIALIZABLE_READ =
      new TransactionMode(IsolationLevel.SERIALIZABLE, true);

  /** Serializable isolation, read-write. */
  public static final TransactionMode SERIALIZABLE_WRITE =
      new TransactionMode(IsolationLevel.SERIALIZABLE, false);

  /**
   * Returns a copy of these settings with the given isolation level.
   *
   * @param level the isolation level to apply
   * @return a new {@code TransactionMode}
   */
  public TransactionMode withIsolationLevel(IsolationLevel level) {
    Objects.requireNonNull(level, "level");
    return new TransactionMode(level, readOnly);
  }

  /**
   * Returns a copy of these settings with the given read-only flag.
   *
   * @param readOnly whether the transaction should be read-only
   * @return a new {@code TransactionMode}
   */
  public TransactionMode withReadOnly(boolean readOnly) {
    return new TransactionMode(isolationLevel, readOnly);
  }
}
