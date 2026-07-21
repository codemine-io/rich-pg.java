package io.codemine.java.richpg;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared, production-grade database session for pgenie-generated rich-pg clients.
 *
 * <p>The session owns a private HikariCP connection pool built from {@link SessionSettings}. It
 * exposes generic {@link #execute(Statement)} and {@link #executeTransaction(Transaction)} methods,
 * OpenTelemetry traces and metrics (via {@link Telemetry}, which also owns the SLF4J logger), a
 * health check, and graceful shutdown.
 *
 * <p>Instances are thread-safe; concurrent calls are supported. {@link #close()} is idempotent.
 *
 * <p>{@link #execute} always retries per {@link RetryStrategy}, up to {@link
 * SessionSettings#retryAttempts()} attempts; there is no non-retrying entry point. {@link
 * #executeTransaction} retries the whole transaction body up to the same {@link
 * SessionSettings#retryAttempts()} bound when a failure is transaction-retryable.
 */
public class Session implements AutoCloseable {

  private final SessionSettings settings;
  private final HikariDataSource dataSource;
  private final Telemetry telemetry;

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final ScheduledExecutorService healthCheckExecutor;
  private volatile boolean healthy;

  /**
   * Opens a session from the given settings.
   *
   * <p>The session will own a private HikariCP pool that is torn down when {@link #close()} is
   * called.
   *
   * @param settings the rich-pg session settings
   * @throws NullPointerException if {@code settings} is null
   */
  public Session(SessionSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.dataSource = settings.toHikariDataSource();
    this.telemetry = Telemetry.forSession(settings, dataSource.getHikariPoolMXBean());
    this.healthCheckExecutor = startHealthCheckProbe();
  }

  /**
   * Package-private constructor for testing — uses a pre-built data source instead of creating one
   * from settings, and does not start the background health probe so mocked connections aren't
   * consumed by it.
   */
  Session(SessionSettings settings, HikariDataSource dataSource) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.telemetry = Telemetry.forSession(settings, dataSource.getHikariPoolMXBean());
    this.healthCheckExecutor = null;
  }

  /**
   * Execute any generated statement record, always retrying it per {@link RetryStrategy}: a
   * statement is retried when the failure's SQLSTATE is safe to retry given whether the statement
   * is declared {@link Statement#idempotent() idempotent}, regardless of any explicit opt-in.
   *
   * <p>The statement is run on a connection borrowed from the internal pool. The statement span is
   * parented to the current OpenTelemetry span, if any. SQLSTATE {@code 40001}/{@code 40P01} are
   * retried on the same connection regardless of idempotency, while SQLSTATE class {@code 08} is
   * retried on a freshly borrowed connection only when the statement is idempotent. Retries are
   * bounded by {@link SessionSettings#retryAttempts()}. Any {@link SQLException} from the final
   * attempt is propagated to the caller.
   *
   * @param statement the statement to execute
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R execute(Statement<R> statement) throws SQLException {
    return execute(statement, Span.current());
  }

  /**
   * Execute any generated statement record with an explicit parent span. See {@link
   * #execute(Statement)} for the retry rules.
   *
   * <p>The statement is run on a connection borrowed from the internal pool. The emitted statement
   * span will be a child of the supplied {@code parentSpan}. Any {@link SQLException} from the
   * final attempt is propagated to the caller.
   *
   * @param statement the statement to execute
   * @param parentSpan the parent span for the statement trace
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R execute(Statement<R> statement, Span parentSpan) throws SQLException {
    ensureOpen();
    Objects.requireNonNull(statement, "statement");

    try (Telemetry.StatementOperationHandle operation =
            telemetry.startStatementOperation(statement, settings.retryAttempts(), parentSpan);
        var scope = operation.span().makeCurrent()) {
      Connection connection = dataSource.getConnection();
      try {
        for (int attempt = 1; ; attempt++) {
          long attemptStart = System.nanoTime();
          try {
            R result = statement.execute(connection);
            operation.finish(attempt, Telemetry.Outcome.SUCCEEDED, null);
            return result;
          } catch (SQLException failure) {
            Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStart);
            RetryStrategy strategy = RetryStrategy.classify(failure, statement.idempotent());
            boolean retryable = strategy != RetryStrategy.NO_RETRY;
            if (!retryable || attempt >= settings.retryAttempts()) {
              operation.finish(
                  attempt,
                  retryable
                      ? Telemetry.Outcome.RETRIES_EXHAUSTED
                      : Telemetry.Outcome.NON_RETRYABLE_FAILURE,
                  failure);
              throw failure;
            }
            telemetry.recordAttemptFailed(operation.span(), attempt, failure, attemptDuration);
            if (strategy == RetryStrategy.NEW_CONNECTION) {
              // Close the broken connection and open a fresh one for the next attempt.
              connection.close();
              connection = dataSource.getConnection();
            }
          }
        }
      } finally {
        connection.close();
      }
    }
  }

  /**
   * Execute {@code statements} as a single JDBC batch, standalone (not inside a transaction).
   *
   * <p>Unlike {@link #execute(Statement)}, a batch is never retried: it is one JDBC {@code
   * executeBatch()} call on a connection borrowed from the internal pool, so a partial failure
   * partway through the batch cannot be safely repeated. The batch span is parented to the current
   * OpenTelemetry span, if any. See {@link StatementBatch} for the statements' shared-shape
   * requirements.
   *
   * @param statements the statements to execute in batch
   * @return the decoded results, in the same order as the input statements
   * @throws SQLException if a database access error occurs during execution
   * @throws IllegalArgumentException if the statements are null, empty, contain a null element,
   *     return rows, or use different SQL text, operation name, or collection name
   */
  public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) throws SQLException {
    return executeBatch(statements, Span.current());
  }

  /**
   * Execute {@code statements} as a single JDBC batch with an explicit parent span. See {@link
   * #executeBatch(Iterable)} for the execution semantics.
   *
   * @param statements the statements to execute in batch
   * @param parentSpan the parent span for the batch trace
   * @return the decoded results, in the same order as the input statements
   * @throws SQLException if a database access error occurs during execution
   * @throws IllegalArgumentException if the statements are null, empty, contain a null element,
   *     return rows, or use different SQL text, operation name, or collection name
   */
  public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements, Span parentSpan)
      throws SQLException {
    ensureOpen();
    StatementBatch<R> batch = new StatementBatch<>(statements);

    try (Telemetry.BatchOperationHandle operation =
            telemetry.startBatchOperation(batch, parentSpan);
        var scope = operation.span().makeCurrent()) {
      Connection connection = dataSource.getConnection();
      try {
        List<R> results = batch.execute(connection);
        operation.finish(null);
        return results;
      } catch (SQLException failure) {
        operation.finish(failure);
        throw failure;
      } finally {
        connection.close();
      }
    }
  }

  /**
   * Execute a transaction with the default mode.
   *
   * <p>The default mode is {@link TransactionMode#SERIALIZABLE_WRITE}. The transaction span is
   * parented to the current OpenTelemetry span, if any.
   *
   * @param transaction the transaction to execute
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(Transaction<R> transaction) throws SQLException {
    return executeTransaction(
        null, TransactionMode.SERIALIZABLE_WRITE, Span.current(), transaction);
  }

  /**
   * Execute a transaction with the supplied mode.
   *
   * @param mode the transaction mode
   * @param transaction the transaction to execute
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(TransactionMode mode, Transaction<R> transaction)
      throws SQLException {
    return executeTransaction(null, mode, Span.current(), transaction);
  }

  /**
   * Execute a named transaction with the supplied mode.
   *
   * <p>The name becomes the transaction span's name and is recorded as the {@code
   * pgenie.transaction.name} attribute on the span and the duration metric.
   *
   * @param name the transaction name
   * @param mode the transaction mode
   * @param transaction the transaction to execute
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(String name, TransactionMode mode, Transaction<R> transaction)
      throws SQLException {
    Objects.requireNonNull(name, "name");
    return executeTransaction(name, mode, Span.current(), transaction);
  }

  /**
   * Execute a named transaction with the supplied mode and an explicit parent span.
   *
   * <p>The transaction body receives an instrumented execution context whose statements are traced
   * as children of the transaction span.
   *
   * @param name the transaction name
   * @param mode the transaction mode
   * @param parentSpan the parent span for the transaction trace
   * @param transaction the transaction to execute
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(
      String name, TransactionMode mode, Span parentSpan, Transaction<R> transaction)
      throws SQLException {
    ensureOpen();
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(mode, "mode");

    try (Connection connection = dataSource.getConnection()) {
      try (Telemetry.TransactionOperationHandle operation =
          telemetry.startTransactionOperation(mode, name, settings.retryAttempts(), parentSpan)) {
        boolean originalAutoCommit = connection.getAutoCommit();
        int originalIsolation = connection.getTransactionIsolation();
        boolean originalReadOnly = connection.isReadOnly();

        connection.setAutoCommit(false);
        connection.setTransactionIsolation(mode.isolationLevel().jdbcLevel());
        connection.setReadOnly(mode.readOnly());

        try (var scope = operation.span().makeCurrent()) {
          ExecutionContext instrumentedContext =
              new NestedExecutionContext(
                  telemetry, new ConnectionExecutionContext(connection), operation.span());
          for (int attempt = 1; ; attempt++) {
            long attemptStart = System.nanoTime();
            try {
              R result = transaction.run(instrumentedContext);
              connection.commit();
              operation.finish(attempt, Telemetry.Outcome.SUCCEEDED, null);
              return result;
            } catch (Exception e) {
              try {
                connection.rollback();
              } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
              }
              Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStart);
              boolean retryable = new ClassifiedSqlFailure(e).isTransactionRetryable();
              if (!retryable || attempt >= settings.retryAttempts()) {
                SQLException failure = e instanceof SQLException sqlException ? sqlException : null;
                operation.finish(
                    attempt,
                    retryable
                        ? Telemetry.Outcome.RETRIES_EXHAUSTED
                        : Telemetry.Outcome.NON_RETRYABLE_FAILURE,
                    e);
                if (failure != null) {
                  throw failure;
                }
                throw new SQLException("Transaction failed", e);
              }
              telemetry.recordAttemptFailed(operation.span(), attempt, e, attemptDuration);
            }
          }
        } finally {
          try {
            connection.setAutoCommit(originalAutoCommit);
            connection.setTransactionIsolation(originalIsolation);
            connection.setReadOnly(originalReadOnly);
          } catch (SQLException ignoredRestoreFailure) {
            // best-effort restore; the primary outcome already determined what propagates
          }
        }
      }
    }
  }

  /**
   * Report the cached result of the background health probe.
   *
   * <p>A daemon thread probes the database every {@link SessionSettings#healthCheckPeriod()} by
   * round-tripping {@code select 1} on a connection borrowed from the pool, bounded by {@link
   * SessionSettings#healthCheckTimeout()}. Because the probe competes for a pooled connection like
   * any caller, a saturated or unreachable pool degrades the health state — matching
   * readiness-probe semantics ("can this instance serve traffic?") rather than bare database
   * reachability. An eager first probe runs at session open; this method never touches the pool on
   * the calling thread.
   *
   * @return {@code true} if the most recent probe's database round-trip succeeded
   */
  public boolean healthCheck() {
    return healthy;
  }

  /** Runs the eager first probe, then schedules the periodic probe on a daemon thread. */
  private ScheduledExecutorService startHealthCheckProbe() {
    probe();
    ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, settings.poolName() + "-health-check");
              thread.setDaemon(true);
              return thread;
            });
    long periodMillis = settings.healthCheckPeriod().toMillis();
    executor.scheduleAtFixedRate(this::probe, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    return executor;
  }

  /** One probe round-trip on a pooled connection; emits a healthCheck span, updates the cache. */
  private void probe() {
    Span span = telemetry.startHealthCheckSpan();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement preparedStatement = connection.prepareStatement("select 1")) {
      preparedStatement.setQueryTimeout((int) settings.healthCheckTimeout().toSeconds());
      try (ResultSet resultSet = preparedStatement.executeQuery()) {
        healthy = resultSet.next();
        span.setStatus(StatusCode.OK);
      }
    } catch (Exception e) {
      healthy = false;
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, e.getMessage());
    } finally {
      span.end();
    }
  }

  /**
   * Gracefully close the session.
   *
   * <p>Waits up to the configured {@link SessionSettings#closeDrainDeadline()} for active
   * connections to drain, then tears down the internal HikariCP pool. Emits a {@code session.close}
   * span recording whether any connections remained active at the deadline.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    if (healthCheckExecutor != null) {
      healthCheckExecutor.shutdownNow();
    }
    healthy = false;

    Telemetry.CloseHandle close = telemetry.startClose();

    HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
    Instant deadline = Instant.now().plus(settings.closeDrainDeadline());
    while (pool != null && pool.getActiveConnections() > 0 && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    int remaining = pool != null ? pool.getActiveConnections() : 0;

    dataSource.close();

    close.finish(remaining);
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("Session is closed");
    }
  }
}
