package io.codemine.java.richpg;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.IsolationLevel;
import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.Transaction;
import io.codemine.java.postgresql.jdbc.TransactionSettings;
import io.codemine.java.richpg.observability.SessionObservability;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared, production-grade database session for pgenie-generated rich-pg clients.
 *
 * <p>The session owns a private HikariCP connection pool built from {@link RichPgConfig}. It
 * exposes generic {@link #execute(Statement)} and {@link #executeTransaction(Transaction)} methods,
 * OpenTelemetry traces and metrics, SLF4J logging, a health check, and graceful shutdown.
 *
 * <p>Instances are thread-safe; concurrent calls are supported. {@link #close()} is idempotent.
 */
public class Session implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Session.class);

  private final RichPgConfig config;
  private final HikariDataSource dataSource;
  private final SessionObservability observability;

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
  public Session(RichPgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = config.toHikariDataSource();
    this.observability = SessionObservability.fromConfig(config, dataSource.getHikariPoolMXBean());
  }

  /**
   * Execute any generated statement record.
   *
   * <p>The statement is run on a connection borrowed from the internal pool. The statement span is
   * parented to the current OpenTelemetry span, if any. Any {@link SQLException} is propagated to
   * the caller.
   *
   * @param statement the statement to execute
   * @return the decoded statement result
   * @throws SQLException if a database access error occurs
   */
  public <R> R execute(Statement<R> statement) throws SQLException {
    return execute(statement, Span.current());
  }

  /**
   * Execute any generated statement record with an explicit parent span.
   *
   * <p>The statement is run on a connection borrowed from the internal pool. The emitted statement
   * span will be a child of the supplied {@code parentSpan}. Any {@link SQLException} is propagated
   * to the caller.
   *
   * @param statement the statement to execute
   * @param parentSpan the parent span for the statement trace
   * @return the decoded statement result
   * @throws SQLException if a database access error occurs
   */
  public <R> R execute(Statement<R> statement, Span parentSpan) throws SQLException {
    ensureOpen();
    Objects.requireNonNull(statement, "statement");

    try (Connection connection = dataSource.getConnection()) {
      return observability
          .forStatement(statement, parentSpan)
          .execute(() -> statement.executeOn(connection));
    }
  }

  /**
   * Execute a statement outside of a transaction, retrying it with default settings when it is
   * declared {@link Statement#idempotent() idempotent} and the failure is safe to retry.
   *
   * <p>The statement span is parented to the current OpenTelemetry span, if any.
   *
   * @param statement the statement to execute
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R executeRetryable(Statement<R> statement) throws SQLException {
    return executeRetryable(statement, StatementSettings.DEFAULT, Span.current());
  }

  /**
   * Execute a statement outside of a transaction with the supplied retry settings.
   *
   * <p>The statement span is parented to the current OpenTelemetry span, if any.
   *
   * @param statement the statement to execute
   * @param settings the statement settings
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R executeRetryable(Statement<R> statement, StatementSettings settings)
      throws SQLException {
    return executeRetryable(statement, settings, Span.current());
  }

  /**
   * Execute a statement outside of a transaction with the supplied retry settings and an explicit
   * parent span.
   *
   * <p>Retries the statement when it is declared {@link Statement#idempotent() idempotent} and the
   * failure is safe to retry: SQLSTATE {@code 40001}/{@code 40P01} are retried on the same
   * connection regardless of idempotency, while SQLSTATE class {@code 08} is retried on a freshly
   * borrowed connection only when the statement is idempotent.
   *
   * @param statement the statement to execute
   * @param settings the statement settings
   * @param parentSpan the parent span for the statement retry trace
   * @return the decoded statement result
   * @throws SQLException if every attempt fails, or if the first failure is not retryable
   */
  public <R> R executeRetryable(Statement<R> statement, StatementSettings settings, Span parentSpan)
      throws SQLException {
    ensureOpen();
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(settings, "settings");

    StatementExecutor executor = new StatementExecutor(observability.forStatementRetry());
    return executor.execute(statement, settings, dataSource::getConnection, parentSpan);
  }

  /**
   * Execute a transaction using default settings derived from the session configuration.
   *
   * <p>The default isolation level is {@link IsolationLevel#SERIALIZABLE}, the transaction is
   * read-write, and the maximum number of attempts is taken from {@link
   * RichPgConfig#transactionRetryAttempts()}. The transaction span is parented to the current
   * OpenTelemetry span, if any.
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
    return executeTransaction(
        transaction,
        new TransactionSettings(
            IsolationLevel.SERIALIZABLE, false, config.transactionRetryAttempts()),
        parentSpan);
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
      TransactionExecutor executor =
          new TransactionExecutor(observability.forTransaction(settings, parentSpan));
      return executor.execute(transaction, settings, connection, parentSpan);
    }
  }

  /**
   * Perform a short-timeout health check by round-tripping the database.
   *
   * @return {@code true} if the database round-trip succeeds, {@code false} otherwise
   */
  public boolean healthCheck() {
    Span span = observability.startHealthCheckSpan();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement preparedStatement = connection.prepareStatement("select 1")) {
      preparedStatement.setQueryTimeout(2);
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
   * <p>Waits up to a 10-second deadline for active connections to drain, then tears down the
   * internal HikariCP pool. Emits a {@code session.close} span recording whether any connections
   * remained active at the deadline.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    SessionObservability.CloseHandle close = observability.startClose();

    HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
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
