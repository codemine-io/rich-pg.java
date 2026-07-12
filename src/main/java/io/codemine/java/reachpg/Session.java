package io.codemine.java.reachpg;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.IsolationLevel;
import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.Transaction;
import io.codemine.java.postgresql.jdbc.TransactionSettings;
import io.codemine.java.reachpg.observability.SessionObservability;
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
 * Shared, production-grade database session for pgenie-generated reach-pg clients.
 *
 * <p>The session owns a private HikariCP connection pool built from {@link ReachPgConfig}. It
 * exposes generic {@link #execute(Statement)} and {@link #executeTransaction(Transaction)} methods,
 * OpenTelemetry traces and metrics, SLF4J logging, a health check, and graceful shutdown.
 *
 * <p>Instances are thread-safe; concurrent calls are supported. {@link #close()} is idempotent.
 */
public class Session implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Session.class);

  private final ReachPgConfig config;
  private final HikariDataSource dataSource;
  private final SessionObservability observability;

  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Opens a session from the given configuration.
   *
   * <p>The session will own a private HikariCP pool that is torn down when {@link #close()} is
   * called.
   *
   * @param config the reach-pg configuration
   * @throws NullPointerException if {@code config} is null
   */
  public Session(ReachPgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dataSource = createHikariDataSource(config);
    this.observability = SessionObservability.fromConfig(config, dataSource.getHikariPoolMXBean());
  }

  private static HikariDataSource createHikariDataSource(ReachPgConfig config) {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(config.jdbcUrl());
    hc.setUsername(config.user());
    hc.setPassword(config.password());
    hc.setMaximumPoolSize(config.maximumPoolSize());
    hc.setConnectionTimeout(config.connectionTimeout().toMillis());
    hc.setPoolName(config.poolName());
    if (config.statementTimeout().toMillis() > 0) {
      hc.setConnectionInitSql(
          "SET statement_timeout = '" + config.statementTimeout().toMillis() + "ms'");
    }
    return new HikariDataSource(hc);
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
   * Execute a transaction using default settings derived from the session configuration.
   *
   * <p>The default isolation level is {@link IsolationLevel#SERIALIZABLE}, the transaction is
   * read-write, and the maximum number of attempts is taken from {@link
   * ReachPgConfig#transactionRetryAttempts()}. The transaction span is parented to the current
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
