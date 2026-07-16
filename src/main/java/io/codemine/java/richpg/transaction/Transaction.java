package io.codemine.java.richpg.transaction;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A unit of work run atomically against a {@link TransactionContext}.
 *
 * @param <R> the result type produced by {@link #run}
 */
@FunctionalInterface
public interface Transaction<R> {

  /**
   * The body of the transaction. May call {@link ExecutionContext#execute(Statement)} any number of
   * times against {@code context}.
   *
   * @param context the execution context to run against
   * @return the result of the transaction
   * @throws SQLException if a database access error occurs
   */
  R run(ExecutionContext context) throws SQLException;

  /**
   * Runs this transaction using {@link TransactionSettings#SERIALIZABLE_WRITE}.
   *
   * @param context the transaction context to use
   * @return the result of {@link #run}
   * @throws SQLException if a database access error occurs while executing the transaction
   */
  default R execute(TransactionContext context) throws SQLException {
    return execute(context, TransactionSettings.SERIALIZABLE_WRITE);
  }

  /**
   * Runs this transaction atomically: disables autocommit, applies {@code settings}, runs {@link
   * #run}, commits on success, and rolls back on any failure &mdash; including an {@link Error}, so
   * that restoring autocommit afterward can never implicitly commit a partially-run transaction.
   * The context's original autocommit, isolation level and read-only state are restored before
   * returning or throwing; if restoring that state itself fails, the failure is attached to the
   * original one via {@link Throwable#addSuppressed} rather than replacing it.
   *
   * @param context the transaction context to use
   * @param settings the settings to apply for this execution
   * @return the result of {@link #run}
   * @throws SQLException if a database access error occurs while executing the transaction
   */
  default R execute(TransactionContext context, TransactionSettings settings) throws SQLException {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(settings, "settings");

    boolean originalAutoCommit = context.getAutoCommit();
    int originalIsolation = context.getTransactionIsolation();
    boolean originalReadOnly = context.isReadOnly();

    context.setAutoCommit(false);
    context.setTransactionIsolation(settings.isolationLevel().jdbcLevel());
    context.setReadOnly(settings.readOnly());

    R result;
    try {
      result = executeAttempts(context, settings);
    } catch (Throwable t) {
      if (!(t instanceof Exception)) {
        try {
          context.rollback();
        } catch (SQLException suppressed) {
          t.addSuppressed(suppressed);
        }
      }
      try {
        context.setAutoCommit(originalAutoCommit);
        context.setTransactionIsolation(originalIsolation);
        context.setReadOnly(originalReadOnly);
      } catch (SQLException suppressed) {
        t.addSuppressed(suppressed);
      }
      throw t;
    }

    context.setAutoCommit(originalAutoCommit);
    context.setTransactionIsolation(originalIsolation);
    context.setReadOnly(originalReadOnly);
    return result;
  }

  /**
   * Runs {@link #run} in a loop, committing on success and rolling back and retrying on a retryable
   * {@link SQLException}, up to {@code settings.maxAttempts()}.
   */
  private R executeAttempts(TransactionContext context, TransactionSettings settings)
      throws SQLException {
    for (int attempt = 1; ; attempt++) {
      try {
        R result = run(context);
        context.commit();
        return result;
      } catch (Exception e) {
        try {
          context.rollback();
        } catch (SQLException suppressed) {
          e.addSuppressed(suppressed);
        }
        if (attempt >= settings.maxAttempts()) {
          throw e;
        }
        boolean retryable = false;
        if (e instanceof SQLException sqlException) {
          String state = sqlException.getSQLState();
          retryable =
              state != null
                  && (state.equals("40001") || state.equals("40P01") || state.equals("23505"));
        }
        if (!retryable) {
          throw e;
        }
      }
    }
  }

  /**
   * Adapts a {@link Statement} into a {@code Transaction} for use in composition.
   *
   * @param statement the statement to adapt
   * @param <R> the statement's result type
   * @return a transaction that runs {@code statement} against the given context
   */
  static <R> Transaction<R> of(Statement<R> statement) {
    Objects.requireNonNull(statement, "statement");
    return (ExecutionContext context) -> context.execute(statement);
  }

  /**
   * Sequences this and {@code next} into one transaction sharing the same context: {@code next}
   * only runs if this transaction's {@link #run} completes normally, and both run within the same
   * commit/rollback boundary.
   *
   * @param next the transaction to run after this one
   * @param <R2> the result type of {@code next}
   * @return a transaction that runs this transaction, then {@code next}
   */
  default <R2> Transaction<R2> andThen(Transaction<? extends R2> next) {
    Objects.requireNonNull(next, "next");
    return (ExecutionContext context) -> {
      run(context);
      return next.run(context);
    };
  }

  /**
   * Transforms this transaction's result after it runs.
   *
   * @param mapper the function to apply to this transaction's result
   * @param <R2> the transformed result type
   * @return a transaction producing the mapped result
   */
  default <R2> Transaction<R2> map(Function<? super R, ? extends R2> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    return (ExecutionContext context) -> mapper.apply(run(context));
  }

  /**
   * Composes this transaction with another transaction produced from its result.
   *
   * <p>Both this transaction and the one returned by {@code mapper} run within the same
   * commit/rollback boundary.
   *
   * @param mapper the function that receives this transaction's result and returns the next
   *     transaction to run
   * @param <R2> the result type of the composed transaction
   * @return a transaction that runs this transaction, then the transaction returned by {@code
   *     mapper}
   */
  default <R2> Transaction<R2> flatMap(Function<? super R, Transaction<R2>> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    return (ExecutionContext context) -> mapper.apply(run(context)).run(context);
  }

  /**
   * Falls back to {@code alternative} if this transaction fails.
   *
   * <p>Runs this transaction under a savepoint. On success, releases the savepoint and returns the
   * result. On a {@link SQLException} whose SQLSTATE is {@code 40001} (serialization failure) or
   * {@code 40P01} (deadlock detected), rethrows it untouched: those failures are transaction-wide
   * and a savepoint rollback cannot heal them, so they must reach {@link #execute}'s retry loop
   * instead. On any other failure &mdash; a {@link SQLException} with a different SQLSTATE, an
   * unchecked exception, or an {@link Error} &mdash; rolls back to the savepoint and releases it
   * (PostgreSQL keeps a savepoint alive after rolling back to it, so it must be released explicitly
   * to avoid it lingering for the rest of the transaction), then runs {@code alternative}. If
   * {@code alternative} also fails, the original failure is attached to it via {@link
   * Throwable#addSuppressed}.
   *
   * @param alternative the transaction to run if this one fails recoverably
   * @return a transaction that falls back to {@code alternative} on recoverable failure
   */
  default Transaction<R> or(Transaction<? extends R> alternative) {
    Objects.requireNonNull(alternative, "alternative");
    return (ExecutionContext context) -> {
      Savepoint savepoint = context.setSavepoint();
      try {
        R result = run(context);
        context.releaseSavepoint(savepoint);
        return result;
      } catch (Throwable t) {
        if (t instanceof SQLException e) {
          String state = e.getSQLState();
          if (state != null && (state.equals("40001") || state.equals("40P01"))) {
            throw e;
          }
        }
        try {
          context.rollback(savepoint);
          context.releaseSavepoint(savepoint);
        } catch (SQLException suppressed) {
          t.addSuppressed(suppressed);
        }
        try {
          return alternative.run(context);
        } catch (Throwable t2) {
          t2.addSuppressed(t);
          throw t2;
        }
      }
    };
  }

  /**
   * A transaction that always fails, without touching the context. Acts as the identity element for
   * {@link #or}: {@code Transaction.<R>empty().or(x)} behaves as {@code x}.
   *
   * @param <R> the result type
   * @return a transaction that always throws when run
   */
  static <R> Transaction<R> empty() {
    return (ExecutionContext context) -> {
      throw new SQLException("Transaction.empty() has no alternative");
    };
  }

  /**
   * Runs each of {@code alternatives} in turn, via {@link #or}, until one succeeds.
   *
   * <p>An empty list is allowed: the returned transaction fails lazily, only once run, the same way
   * {@link #empty} does.
   *
   * @param alternatives the transactions to try, in order
   * @param <R> the result type
   * @return a transaction that runs the first of {@code alternatives} to succeed
   */
  static <R> Transaction<R> firstOf(List<Transaction<R>> alternatives) {
    if (alternatives == null) {
      return Transaction.empty();
    }
    return alternatives.stream().reduce(Transaction.empty(), Transaction::or);
  }

  /**
   * Runs each of {@code alternatives} in turn, via {@link #or}, until one succeeds.
   *
   * @param alternatives the transactions to try, in order
   * @param <R> the result type
   * @return a transaction that runs the first of {@code alternatives} to succeed
   */
  @SafeVarargs
  static <R> Transaction<R> firstOf(Transaction<R>... alternatives) {
    if (alternatives == null) {
      return Transaction.empty();
    }
    return Arrays.stream(alternatives).reduce(Transaction.empty(), Transaction::or);
  }
}
