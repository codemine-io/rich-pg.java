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
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared, production-grade database session for pgenie-generated rich-pg clients.
 *
 * <p>The session owns a private HikariCP connection pool built from {@link SessionSettings}. It
 * exposes generic {@link #execute(Statement)} and {@link #executeTransaction(Transaction)} methods,
 * OpenTelemetry traces and metrics, SLF4J logging, a health check, and graceful shutdown.
 *
 * <p>Instances are thread-safe; concurrent calls are supported. {@link #close()} is idempotent.
 *
 * <p>{@link #execute} always retries per {@link SqlStateClassifier}, up to {@link
 * SessionSettings#retryAttempts()} attempts; there is no non-retrying entry point.
 *
 * <p>TODO(Task 6): {@link #executeTransaction} currently delegates to a temporary compiling stub
 * ({@link TransactionExecutor}) that does not yet retry or record telemetry; the full rewrite lands
 * in a later task of the design-revision plan.
 */
public class Session implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Session.class);

  private final SessionSettings config;
  private final HikariDataSource dataSource;
  private final Telemetry telemetry;

  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Opens a session from the given configuration.
   *
   * <p>The session will own a private HikariCP pool that is torn down when {@link #close()} is
   * called.
   *
   * @param config the rich-pg configuration
   * @throws NullPointerException if {@code config} is null
   */
  public Session(SessionSettings config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = config.toHikariDataSource();
    this.telemetry = Telemetry.forSession(config, dataSource.getHikariPoolMXBean());
  }

  /**
   * Execute any generated statement record, retrying it when it is declared {@link
   * Statement#idempotent() idempotent} and the failure is safe to retry.
   *
   * <p>The statement is run on a connection borrowed from the internal pool. The statement span is
   * parented to the current OpenTelemetry span, if any. SQLSTATE {@code 40001}/{@code 40P01}/{@code
   * 23505} are retried on the same connection regardless of idempotency, while SQLSTATE class
   * {@code 08} is retried on a freshly borrowed connection only when the statement is idempotent.
   * Retries are bounded by {@link SessionSettings#retryAttempts()}. Any {@link SQLException} from
   * the final attempt is propagated to the caller.
   *
   * @param statement the statement to execute
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R execute(Statement<R> statement) throws SQLException {
    return execute(statement, Span.current());
  }

  /**
   * Execute any generated statement record with an explicit parent span, retrying it when it is
   * declared {@link Statement#idempotent() idempotent} and the failure is safe to retry.
   *
   * <p>The statement is run on a connection borrowed from the internal pool. The emitted statement
   * span will be a child of the supplied {@code parentSpan}. See {@link #execute(Statement)} for
   * the retry rules. Any {@link SQLException} from the final attempt is propagated to the caller.
   *
   * @param statement the statement to execute
   * @param parentSpan the parent span for the statement trace
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R execute(Statement<R> statement, Span parentSpan) throws SQLException {
    ensureOpen();
    Objects.requireNonNull(statement, "statement");

    StatementExecutor executor = new StatementExecutor(telemetry);
    return executor.execute(
        statement, config.retryAttempts(), dataSource::getConnection, parentSpan);
  }

  /**
   * Execute a transaction using default settings derived from the session configuration.
   *
   * <p>The default isolation level is {@link IsolationLevel#SERIALIZABLE} and the transaction is
   * read-write. The transaction span is parented to the current OpenTelemetry span, if any.
   *
   * @param transaction the transaction to execute
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(Transaction<R> transaction) throws SQLException {
    return executeTransaction(transaction, Span.current());
  }

  /**
   * Execute a transaction using default settings with an explicit parent span.
   *
   * @param transaction the transaction to execute
   * @param parentSpan the parent span for the transaction trace
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(Transaction<R> transaction, Span parentSpan) throws SQLException {
    return executeTransaction(transaction, TransactionSettings.SERIALIZABLE_WRITE, parentSpan);
  }

  /**
   * Execute a transaction with the supplied settings.
   *
   * @param transaction the transaction to execute
   * @param settings the transaction settings
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(Transaction<R> transaction, TransactionSettings settings)
      throws SQLException {
    return executeTransaction(transaction, settings, Span.current());
  }

  /**
   * Execute a transaction with the supplied settings and an explicit parent span.
   *
   * <p>The transaction body receives an instrumented execution context whose statements are traced
   * as children of the transaction span.
   *
   * @param transaction the transaction to execute
   * @param settings the transaction settings
   * @param parentSpan the parent span for the transaction trace
   * @return the transaction result
   * @throws SQLException if a database access error occurs
   */
  public <R> R executeTransaction(
      Transaction<R> transaction, TransactionSettings settings, Span parentSpan)
      throws SQLException {
    ensureOpen();
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(settings, "settings");

    try (Connection connection = dataSource.getConnection()) {
      TransactionExecutor executor = new TransactionExecutor(telemetry);
      return executor.execute(transaction, settings, connection, parentSpan);
    }
  }

  /**
   * Perform a short-timeout health check by round-tripping the database.
   *
   * @return {@code true} if the database round-trip succeeds, {@code false} otherwise
   */
  public boolean healthCheck() {
    Span span = telemetry.startHealthCheckSpan();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement preparedStatement = connection.prepareStatement("select 1")) {
      preparedStatement.setQueryTimeout((int) config.healthCheckTimeout().toSeconds());
      try (ResultSet resultSet = preparedStatement.executeQuery()) {
        boolean ok = resultSet.next();
        span.setStatus(StatusCode.OK);
        return ok;
      }
    } catch (SQLException e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, e.getMessage());
      return false;
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

    Telemetry.CloseHandle close = telemetry.startClose();

    HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
    Instant deadline = Instant.now().plus(config.closeDrainDeadline());
    while (pool != null && pool.getActiveConnections() > 0 && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    int remaining = pool != null ? pool.getActiveConnections() : 0;
    if (remaining > 0) {
      logger.warn("{} active connection(s) remained at close deadline", remaining);
    }

    dataSource.close();

    close.finish(remaining);
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("Session is closed");
    }
  }
}
