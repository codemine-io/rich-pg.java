package io.codemine.java.richpg;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.StatementBatch;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single OpenTelemetry/SLF4J instrumentation surface for a session.
 *
 * <p>Owns the one {@link Tracer}, the one {@code db.client.operation.duration} histogram, the
 * statement- and transaction-retry counters, the pool gauges, the SLF4J logger, the db user, the
 * slow-query threshold and the artifact name. Built once via {@link #forSession}. All attribute
 * keys and span/metric names live here. Core execution code (statement executor, transaction
 * executor, {@link Session}) owns retry loops and calls this class's methods to notify telemetry at
 * well-defined moments; this class never drives control flow.
 */
final class Telemetry {

  private static final String DB_SYSTEM = "postgresql";
  private static final String METRIC_NAME = "db.client.operation.duration";

  private static final AttributeKey<String> DB_SYSTEM_NAME =
      AttributeKey.stringKey("db.system.name");
  private static final AttributeKey<String> DB_QUERY_TEXT = AttributeKey.stringKey("db.query.text");
  private static final AttributeKey<String> DB_OPERATION_NAME =
      AttributeKey.stringKey("db.operation.name");
  private static final AttributeKey<String> DB_COLLECTION_NAME =
      AttributeKey.stringKey("db.collection.name");
  private static final AttributeKey<String> STATEMENT_NAME =
      AttributeKey.stringKey("pgenie.statement.name");
  private static final AttributeKey<String> DB_USER = AttributeKey.stringKey("pgenie.db.user");
  private static final AttributeKey<Long> BATCH_SIZE =
      AttributeKey.longKey("db.operation.batch.size");
  private static final AttributeKey<String> ARTIFACT_NAME =
      AttributeKey.stringKey("pgenie.artifact.name");
  private static final AttributeKey<String> ISOLATION_LEVEL =
      AttributeKey.stringKey("pgenie.transaction.isolation_level");
  private static final AttributeKey<Boolean> READ_ONLY =
      AttributeKey.booleanKey("pgenie.transaction.read_only");
  private static final AttributeKey<Long> MAX_ATTEMPTS_STMT =
      AttributeKey.longKey("pgenie.statement.max_attempts");
  private static final AttributeKey<Long> MAX_ATTEMPTS_TXN =
      AttributeKey.longKey("pgenie.transaction.max_attempts");
  private static final AttributeKey<Long> ATTEMPT_COUNT_STMT =
      AttributeKey.longKey("pgenie.statement.attempt_count");
  private static final AttributeKey<Long> ATTEMPT_COUNT_TXN =
      AttributeKey.longKey("pgenie.transaction.attempt_count");
  private static final AttributeKey<String> OUTCOME_STMT =
      AttributeKey.stringKey("pgenie.statement.outcome");
  private static final AttributeKey<String> OUTCOME_TXN =
      AttributeKey.stringKey("pgenie.transaction.outcome");
  private static final AttributeKey<Long> CLOSE_CONNECTIONS_REMAINING =
      AttributeKey.longKey("pgenie.session.close.connections_remaining");
  private static final AttributeKey<String> POOL_NAME = AttributeKey.stringKey("pool.name");

  static final String OUTCOME_SUCCEEDED = "succeeded";
  static final String OUTCOME_COMMITTED = "committed";
  static final String OUTCOME_RETRIES_EXHAUSTED = "retries_exhausted";
  static final String OUTCOME_NON_RETRYABLE_FAILURE = "non_retryable_failure";

  private final Tracer tracer;
  private final DoubleHistogram durationHistogram;
  private final LongCounter statementRetries;
  private final LongCounter transactionRetries;
  private final Logger logger;
  private final String dbUser;
  private final String artifactName;
  private final Duration slowQueryLogThreshold;
  private final List<ObservableLongGauge> gauges;
  private final AtomicBoolean gaugesClosed = new AtomicBoolean(false);

  private Telemetry(
      Tracer tracer,
      DoubleHistogram durationHistogram,
      LongCounter statementRetries,
      LongCounter transactionRetries,
      Logger logger,
      String dbUser,
      String artifactName,
      Duration slowQueryLogThreshold,
      List<ObservableLongGauge> gauges) {
    this.tracer = tracer;
    this.durationHistogram = durationHistogram;
    this.statementRetries = statementRetries;
    this.transactionRetries = transactionRetries;
    this.logger = logger;
    this.dbUser = dbUser;
    this.artifactName = artifactName;
    this.slowQueryLogThreshold = slowQueryLogThreshold;
    this.gauges = gauges;
  }

  /**
   * Builds a session's telemetry from its config and pool MX bean, registering the pool gauges and
   * logging the (URL-redacted) "Session opened" line.
   */
  static Telemetry forSession(SessionSettings config, HikariPoolMXBean poolMxBean) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(poolMxBean, "poolMxBean");

    Logger logger = LoggerFactory.getLogger(Telemetry.class);
    Tracer tracer = config.openTelemetry().getTracer(config.scopeName(), config.scopeVersion());
    Meter meter = config.openTelemetry().getMeter(config.scopeName());
    DoubleHistogram durationHistogram =
        meter
            .histogramBuilder(METRIC_NAME)
            .setUnit("s")
            .setDescription("Duration of database client operations")
            .build();
    LongCounter statementRetries =
        meter
            .counterBuilder("pgenie.statement.retries")
            .setDescription("Number of standalone statement retries")
            .build();
    LongCounter transactionRetries =
        meter
            .counterBuilder("pgenie.transaction.retries")
            .setDescription("Number of transaction retries")
            .build();

    List<ObservableLongGauge> gauges = registerPoolGauges(meter, poolMxBean, config.poolName());

    logger.info(
        "Session opened for jdbcUrl={} user={} artifact={}",
        redactUrl(config.jdbcUrl()),
        config.user(),
        config.artifactName());

    return new Telemetry(
        tracer,
        durationHistogram,
        statementRetries,
        transactionRetries,
        logger,
        config.user(),
        config.artifactName(),
        config.slowQueryLogThreshold(),
        gauges);
  }

  private static List<ObservableLongGauge> registerPoolGauges(
      Meter meter, HikariPoolMXBean poolMxBean, String poolName) {
    List<ObservableLongGauge> gauges = new ArrayList<>(4);
    Attributes attributes = Attributes.of(POOL_NAME, poolName);
    gauges.add(
        meter
            .gaugeBuilder("pgenie.pool.connections.active")
            .setDescription("Active connections in the HikariCP pool")
            .ofLongs()
            .buildWithCallback(m -> m.record(poolMxBean.getActiveConnections(), attributes)));
    gauges.add(
        meter
            .gaugeBuilder("pgenie.pool.connections.idle")
            .setDescription("Idle connections in the HikariCP pool")
            .ofLongs()
            .buildWithCallback(m -> m.record(poolMxBean.getIdleConnections(), attributes)));
    gauges.add(
        meter
            .gaugeBuilder("pgenie.pool.connections.pending")
            .setDescription("Threads waiting for a connection from the HikariCP pool")
            .ofLongs()
            .buildWithCallback(
                m -> m.record(poolMxBean.getThreadsAwaitingConnection(), attributes)));
    gauges.add(
        meter
            .gaugeBuilder("pgenie.pool.connections.total")
            .setDescription("Total connections in the HikariCP pool")
            .ofLongs()
            .buildWithCallback(m -> m.record(poolMxBean.getTotalConnections(), attributes)));
    return gauges;
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
   * Starts a standalone CLIENT statement span (covers all attempts), parented to {@code parentSpan}
   * or the current context.
   */
  StatementHandle startStatement(Statement<?> statement, Span parentSpan) {
    return startStatementSpan(
        statement.statementName(),
        statement.sql(),
        statement.operationName(),
        statement.collectionName(),
        null,
        parentSpan);
  }

  /** Starts a batch CLIENT span (covers all attempts). */
  StatementHandle startBatch(
      StatementBatch<?> batch, Statement<?> representative, Span parentSpan) {
    return startStatementSpan(
        "batch",
        batch.sql(),
        representative.operationName(),
        representative.collectionName(),
        batch.size(),
        parentSpan);
  }

  /**
   * Starts a single-attempt CLIENT statement span nested under a transaction span (no retry span
   * layer).
   */
  StatementHandle startNestedStatement(Statement<?> statement, Span transactionSpan) {
    return startStatement(statement, transactionSpan);
  }

  StatementHandle startNestedBatch(
      StatementBatch<?> batch, Statement<?> representative, Span transactionSpan) {
    return startBatch(batch, representative, transactionSpan);
  }

  private StatementHandle startStatementSpan(
      String statementName,
      String sql,
      Optional<String> operationName,
      Optional<String> collectionName,
      Integer batchSize,
      Span parentSpan) {
    var builder =
        tracer
            .spanBuilder(statementName)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
            .setAttribute(DB_QUERY_TEXT, sql)
            .setAttribute(STATEMENT_NAME, statementName)
            .setAttribute(DB_USER, dbUser)
            .setAttribute(ARTIFACT_NAME, artifactName);
    operationName.ifPresent(v -> builder.setAttribute(DB_OPERATION_NAME, v));
    collectionName.ifPresent(v -> builder.setAttribute(DB_COLLECTION_NAME, v));
    if (batchSize != null) {
      builder.setAttribute(BATCH_SIZE, (long) batchSize);
    }
    if (parentSpan != null) {
      builder.setParent(Context.current().with(parentSpan));
    }
    return new StatementHandle(
        builder.startSpan(), statementName, sql, operationName, collectionName);
  }

  /** A started statement span plus what's needed to record its duration/outcome. */
  final class StatementHandle {
    private final Span span;
    private final String statementName;
    private final String sql;
    private final Optional<String> operationName;
    private final Optional<String> collectionName;
    private final long startNanos = System.nanoTime();

    private StatementHandle(
        Span span,
        String statementName,
        String sql,
        Optional<String> operationName,
        Optional<String> collectionName) {
      this.span = span;
      this.statementName = statementName;
      this.sql = sql;
      this.operationName = operationName;
      this.collectionName = collectionName;
    }

    Span span() {
      return span;
    }

    /** Records success, ends the span. */
    void succeeded() {
      span.setStatus(StatusCode.OK);
      finish();
    }

    /** Records failure, ends the span. */
    void failed(Throwable t) {
      span.recordException(t);
      span.setStatus(StatusCode.ERROR, t.getMessage());
      finish();
    }

    private void finish() {
      long durationNanos = System.nanoTime() - startNanos;
      double durationSeconds = durationNanos / 1_000_000_000.0;
      var attrs =
          Attributes.builder()
              .put(DB_SYSTEM_NAME, DB_SYSTEM)
              .put(DB_QUERY_TEXT, sql)
              .put(STATEMENT_NAME, statementName);
      operationName.ifPresent(v -> attrs.put(DB_OPERATION_NAME, v));
      collectionName.ifPresent(v -> attrs.put(DB_COLLECTION_NAME, v));
      durationHistogram.record(durationSeconds, attrs.build());
      if (Duration.ofNanos(durationNanos).compareTo(slowQueryLogThreshold) > 0) {
        logger.warn("Slow query detected: {} took {} seconds", statementName, durationSeconds);
      }
      span.end();
    }
  }

  /** Records a failed attempt as a span event on {@code operationSpan}, per design §3.1. */
  void recordAttemptFailed(
      Span operationSpan, int attemptNumber, Throwable failure, Duration attemptDuration) {
    operationSpan.addEvent(
        "attempt " + attemptNumber + " failed",
        Attributes.of(
            AttributeKey.stringKey("exception.message"), String.valueOf(failure.getMessage()),
            AttributeKey.stringKey("exception.type"), failure.getClass().getName(),
            AttributeKey.doubleKey("attempt.duration_seconds"),
                attemptDuration.toNanos() / 1_000_000_000.0));
  }

  /** Starts the standalone-statement operation span (parent of all attempt spans + events). */
  Span startStatementOperation(Statement<?> statement, int maxAttempts, Span parentSpan) {
    var builder =
        tracer
            .spanBuilder(statement.statementName())
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
            .setAttribute(ARTIFACT_NAME, artifactName)
            .setAttribute(MAX_ATTEMPTS_STMT, (long) maxAttempts);
    if (parentSpan != null) {
      builder.setParent(Context.current().with(parentSpan));
    }
    return builder.startSpan();
  }

  void finishStatementOperation(
      Span span, int attempts, boolean succeeded, boolean retryable, Throwable failure) {
    String outcome =
        succeeded
            ? OUTCOME_SUCCEEDED
            : (retryable ? OUTCOME_RETRIES_EXHAUSTED : OUTCOME_NON_RETRYABLE_FAILURE);
    span.setAttribute(ATTEMPT_COUNT_STMT, (long) attempts);
    span.setAttribute(OUTCOME_STMT, outcome);
    statementRetries.add(Math.max(0, attempts - 1));
    if (succeeded) {
      span.setStatus(StatusCode.OK);
    } else {
      span.recordException(failure);
      span.setStatus(StatusCode.ERROR, failure.getMessage());
      if (OUTCOME_RETRIES_EXHAUSTED.equals(outcome)) {
        logger.warn("Statement exhausted {} attempts, last failure: {}", attempts, failure);
      }
    }
    span.end();
  }

  /** Starts the INTERNAL transaction operation span. */
  Span startTransactionOperation(TransactionSettings settings, int maxAttempts, Span parentSpan) {
    var builder =
        tracer
            .spanBuilder("transaction")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
            .setAttribute(ARTIFACT_NAME, artifactName)
            .setAttribute(ISOLATION_LEVEL, settings.isolationLevel().name())
            .setAttribute(READ_ONLY, settings.readOnly())
            .setAttribute(MAX_ATTEMPTS_TXN, (long) maxAttempts);
    if (parentSpan != null) {
      builder.setParent(Context.current().with(parentSpan));
    }
    return builder.startSpan();
  }

  void finishTransactionOperation(
      Span span, int attempts, boolean succeeded, boolean retryable, Throwable failure) {
    String outcome =
        succeeded
            ? OUTCOME_COMMITTED
            : (retryable ? OUTCOME_RETRIES_EXHAUSTED : OUTCOME_NON_RETRYABLE_FAILURE);
    span.setAttribute(ATTEMPT_COUNT_TXN, (long) attempts);
    span.setAttribute(OUTCOME_TXN, outcome);
    transactionRetries.add(Math.max(0, attempts - 1));
    if (succeeded) {
      span.setStatus(StatusCode.OK);
    } else {
      span.recordException(failure);
      span.setStatus(StatusCode.ERROR, failure.getMessage());
      if (OUTCOME_RETRIES_EXHAUSTED.equals(outcome)) {
        logger.warn("Transaction exhausted {} attempts, last failure: {}", attempts, failure);
      }
    }
    span.end();
  }

  Span startHealthCheckSpan() {
    return tracer
        .spanBuilder("healthCheck")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
        .startSpan();
  }

  CloseHandle startClose() {
    logger.info("Closing Session");
    if (gaugesClosed.compareAndSet(false, true)) {
      gauges.forEach(ObservableLongGauge::close);
    }
    return new CloseHandle();
  }

  final class CloseHandle {
    private CloseHandle() {}

    void finish(int remainingConnections) {
      Span span =
          tracer
              .spanBuilder("session.close")
              .setSpanKind(SpanKind.INTERNAL)
              .setAttribute(CLOSE_CONNECTIONS_REMAINING, (long) remainingConnections)
              .startSpan();
      try {
        span.setStatus(
            remainingConnections > 0 ? StatusCode.ERROR : StatusCode.OK,
            remainingConnections > 0
                ? remainingConnections + " active connection(s) remained at close deadline"
                : null);
      } finally {
        span.end();
      }
      logger.info("Session closed");
    }
  }
}
