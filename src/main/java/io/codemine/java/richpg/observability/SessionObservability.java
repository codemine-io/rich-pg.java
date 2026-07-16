package io.codemine.java.richpg.observability;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.richpg.RichPgConfig;
import io.codemine.java.richpg.transaction.TransactionSettings;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session-level telemetry: the only OTel {@link Tracer}/{@link Meter} for a session, the {@code
 * db.system.name}/{@code pool.name}/{@code db.user} identity, the per-statement and per-transaction
 * child factories, the {@code pgenie.pool.connections.*} gauges, the health-check and {@code
 * session.close} spans, and the session-opened / closing-session / session-closed log lines.
 *
 * <p>Constructed exclusively via {@link #fromConfig(RichPgConfig, HikariPoolMXBean)}. Not {@link
 * AutoCloseable} — {@link #startClose()} returns a two-phase {@link CloseHandle} instead, because
 * close has a middle (draining the pool) that only {@code Session} can perform.
 */
public final class SessionObservability {

  private static final String DB_SYSTEM = "postgresql";

  static final AttributeKey<String> DB_SYSTEM_NAME_KEY = AttributeKey.stringKey("db.system.name");
  static final AttributeKey<Long> CLOSE_CONNECTIONS_REMAINING_KEY =
      AttributeKey.longKey("pgenie.session.close.connections_remaining");

  private static final String ACTIVE_GAUGE = "pgenie.pool.connections.active";
  private static final String IDLE_GAUGE = "pgenie.pool.connections.idle";
  private static final String PENDING_GAUGE = "pgenie.pool.connections.pending";
  private static final String TOTAL_GAUGE = "pgenie.pool.connections.total";
  private static final AttributeKey<String> POOL_NAME_KEY = AttributeKey.stringKey("pool.name");

  private final Tracer tracer;
  private final DoubleHistogram durationHistogram;
  private final Logger logger;
  private final String dbUser;
  private final Duration slowQueryLogThreshold;
  private final TransactionObservability transactionObservability;
  private final StatementRetryObservability statementRetryObservability;
  private final List<ObservableLongGauge> gauges;
  private final AtomicBoolean gaugesClosed = new AtomicBoolean(false);

  private SessionObservability(
      Tracer tracer,
      DoubleHistogram durationHistogram,
      Logger logger,
      String dbUser,
      Duration slowQueryLogThreshold,
      TransactionObservability transactionObservability,
      StatementRetryObservability statementRetryObservability,
      List<ObservableLongGauge> gauges) {
    this.tracer = tracer;
    this.durationHistogram = durationHistogram;
    this.logger = logger;
    this.dbUser = dbUser;
    this.slowQueryLogThreshold = slowQueryLogThreshold;
    this.transactionObservability = transactionObservability;
    this.statementRetryObservability = statementRetryObservability;
    this.gauges = gauges;
  }

  /**
   * Builds a session's observability from its configuration and pool MX bean.
   *
   * <p>Derives the session's single {@link Tracer}/{@link Meter}/{@link Logger} and shared {@code
   * db.client.operation.duration} histogram, builds the session's {@link TransactionObservability},
   * registers the four {@code pgenie.pool.connections.*} gauges, and logs the (URL-redacted)
   * "Session opened" line.
   *
   * @param config the rich-pg configuration
   * @param poolMxBean the HikariCP pool MX bean to poll for the pool gauges
   * @return a new session observability
   * @throws NullPointerException if either argument is null
   */
  public static SessionObservability fromConfig(RichPgConfig config, HikariPoolMXBean poolMxBean) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(poolMxBean, "poolMxBean");

    Logger logger = LoggerFactory.getLogger(SessionObservability.class);
    Tracer tracer = config.openTelemetry().getTracer(config.scopeName(), config.scopeVersion());
    Meter meter = config.openTelemetry().getMeter(config.scopeName());
    DoubleHistogram durationHistogram = StatementObservability.buildDurationHistogram(meter);

    TransactionObservability transactionObservability =
        new TransactionObservability(
            tracer,
            meter,
            durationHistogram,
            logger,
            config.user(),
            config.slowQueryLogThreshold());

    StatementRetryObservability statementRetryObservability =
        new StatementRetryObservability(
            tracer,
            meter,
            durationHistogram,
            logger,
            config.user(),
            config.slowQueryLogThreshold());

    List<ObservableLongGauge> gauges = registerPoolGauges(meter, poolMxBean, config.poolName());

    logger.info(
        "Session opened for jdbcUrl={} user={}", redactUrl(config.jdbcUrl()), config.user());

    return new SessionObservability(
        tracer,
        durationHistogram,
        logger,
        config.user(),
        config.slowQueryLogThreshold(),
        transactionObservability,
        statementRetryObservability,
        gauges);
  }

  private static List<ObservableLongGauge> registerPoolGauges(
      Meter meter, HikariPoolMXBean poolMxBean, String poolName) {
    List<ObservableLongGauge> gauges = new ArrayList<>(4);
    Attributes attributes = Attributes.of(POOL_NAME_KEY, poolName);

    gauges.add(
        meter
            .gaugeBuilder(ACTIVE_GAUGE)
            .setDescription("Active connections in the HikariCP pool")
            .ofLongs()
            .buildWithCallback(
                measurement -> measurement.record(poolMxBean.getActiveConnections(), attributes)));

    gauges.add(
        meter
            .gaugeBuilder(IDLE_GAUGE)
            .setDescription("Idle connections in the HikariCP pool")
            .ofLongs()
            .buildWithCallback(
                measurement -> measurement.record(poolMxBean.getIdleConnections(), attributes)));

    gauges.add(
        meter
            .gaugeBuilder(PENDING_GAUGE)
            .setDescription("Threads waiting for a connection from the HikariCP pool")
            .ofLongs()
            .buildWithCallback(
                measurement ->
                    measurement.record(poolMxBean.getThreadsAwaitingConnection(), attributes)));

    gauges.add(
        meter
            .gaugeBuilder(TOTAL_GAUGE)
            .setDescription("Total connections in the HikariCP pool")
            .ofLongs()
            .buildWithCallback(
                measurement -> measurement.record(poolMxBean.getTotalConnections(), attributes)));

    return gauges;
  }

  /**
   * Builds a {@link StatementObservability} bound to {@code statement}, its span parented to {@code
   * parentSpan}.
   *
   * @param statement the statement about to be executed
   * @param parentSpan the parent span for the statement execution, or {@code null} to use the
   *     current OpenTelemetry context
   * @return a new statement observability, with its span already started
   */
  public StatementObservability forStatement(Statement<?> statement, Span parentSpan) {
    return StatementObservability.forStatement(
        tracer, durationHistogram, logger, dbUser, slowQueryLogThreshold, statement, parentSpan);
  }

  /**
   * Builds a {@link StatementObservability} bound to {@code statement}, its span parented to {@link
   * Span#current()}.
   *
   * @param statement the statement about to be executed
   * @return a new statement observability, with its span already started
   */
  public StatementObservability forStatement(Statement<?> statement) {
    return forStatement(statement, Span.current());
  }

  /**
   * Returns the session's {@link TransactionObservability} child.
   *
   * @param settings the transaction settings the caller intends to run with
   * @param parentSpan the parent span for the transaction obtained through this child
   * @return the session's transaction observability
   * @throws NullPointerException if {@code settings} is null
   */
  public TransactionObservability forTransaction(TransactionSettings settings, Span parentSpan) {
    Objects.requireNonNull(settings, "settings");
    return transactionObservability.withParentSpan(parentSpan);
  }

  /**
   * Returns the session's {@link TransactionObservability} child, parented to {@link
   * Span#current()}.
   *
   * @param settings the transaction settings the caller intends to run with
   * @return the session's transaction observability
   * @throws NullPointerException if {@code settings} is null
   */
  public TransactionObservability forTransaction(TransactionSettings settings) {
    return forTransaction(settings, Span.current());
  }

  /**
   * Returns the session's {@link StatementRetryObservability} child.
   *
   * @return the session's statement-retry observability
   */
  public StatementRetryObservability forStatementRetry() {
    return statementRetryObservability;
  }

  /**
   * Starts the health-check span.
   *
   * <p>{@code Session} runs the health-check query and ends the returned span itself.
   *
   * @return a new, started {@code "healthCheck"} {@code CLIENT} span
   */
  public Span startHealthCheckSpan() {
    return tracer
        .spanBuilder("healthCheck")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute(DB_SYSTEM_NAME_KEY, DB_SYSTEM)
        .startSpan();
  }

  /**
   * Begins the two-phase session close.
   *
   * <p>Logs {@code "Closing Session"} and closes the four pool gauges. {@code Session} then drains
   * the pool and closes its data source before calling {@link CloseHandle#finish(int)}.
   *
   * @return a handle used to finish the close once the pool has drained
   */
  public CloseHandle startClose() {
    logger.info("Closing Session");
    closeGauges();
    return new CloseHandle(tracer, logger);
  }

  private void closeGauges() {
    if (!gaugesClosed.compareAndSet(false, true)) {
      return;
    }
    for (ObservableLongGauge gauge : gauges) {
      gauge.close();
    }
  }

  static String redactUrl(String url) {
    if (url == null) {
      return null;
    }
    int passwordIndex = url.toLowerCase().indexOf("password=");
    if (passwordIndex == -1) {
      return url;
    }
    int ampersandIndex = url.indexOf('&', passwordIndex);
    if (ampersandIndex == -1) {
      return url.substring(0, passwordIndex + 9) + "***";
    }
    return url.substring(0, passwordIndex + 9) + "***" + url.substring(ampersandIndex);
  }

  /**
   * The second phase of a session close, obtained from {@link #startClose()}.
   *
   * <p>{@link #finish(int)} emits the {@code session.close} span and logs {@code "Session closed"}.
   */
  public static final class CloseHandle {

    private final Tracer tracer;
    private final Logger logger;

    private CloseHandle(Tracer tracer, Logger logger) {
      this.tracer = tracer;
      this.logger = logger;
    }

    /**
     * Emits the {@code session.close} span and logs {@code "Session closed"}.
     *
     * @param remainingConnections the number of active connections remaining at the close deadline;
     *     zero means every connection drained in time
     */
    public void finish(int remainingConnections) {
      Span span =
          tracer
              .spanBuilder("session.close")
              .setSpanKind(SpanKind.INTERNAL)
              .setAttribute(CLOSE_CONNECTIONS_REMAINING_KEY, (long) remainingConnections)
              .startSpan();
      try {
        if (remainingConnections > 0) {
          span.setStatus(
              StatusCode.ERROR,
              remainingConnections + " active connection(s) remained at close deadline");
        } else {
          span.setStatus(StatusCode.OK);
        }
      } finally {
        span.end();
      }

      logger.info("Session closed");
    }
  }
}
