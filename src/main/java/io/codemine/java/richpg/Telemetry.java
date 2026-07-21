package io.codemine.java.richpg;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
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
  private static final String PASSWORD_PARAM = "password=";

  private static final AttributeKey<String> DB_SYSTEM_NAME =
      AttributeKey.stringKey("db.system.name");
  private static final AttributeKey<String> DB_QUERY_TEXT = AttributeKey.stringKey("db.query.text");
  private static final AttributeKey<String> DB_OPERATION_NAME =
      AttributeKey.stringKey("db.operation.name");
  private static final AttributeKey<String> DB_COLLECTION_NAME =
      AttributeKey.stringKey("db.collection.name");
  private static final AttributeKey<String> STATEMENT_NAME =
      AttributeKey.stringKey("pgenie.statement.name");
  private static final AttributeKey<String> OPERATION_TYPE =
      AttributeKey.stringKey("pgenie.operation.type");
  private static final AttributeKey<String> TRANSACTION_NAME =
      AttributeKey.stringKey("pgenie.transaction.name");
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
  private static final AttributeKey<Long> ATTEMPT_NUMBER = AttributeKey.longKey("attempt.number");
  private static final AttributeKey<Double> ATTEMPT_DURATION_SECONDS =
      AttributeKey.doubleKey("attempt.duration_seconds");
  private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

  static final String OPERATION_TYPE_STATEMENT = "statement";
  static final String OPERATION_TYPE_BATCH = "batch";
  static final String OPERATION_TYPE_TRANSACTION = "transaction";

  static final String OUTCOME_SUCCEEDED = "succeeded";
  static final String OUTCOME_COMMITTED = "committed";
  static final String OUTCOME_RETRIES_EXHAUSTED = "retries_exhausted";
  static final String OUTCOME_NON_RETRYABLE_FAILURE = "non_retryable_failure";
  private static final String ERROR_TYPE_UNKNOWN = "unknown";

  /** The terminal state of a retried operation, replacing a positional succeeded/retryable pair. */
  enum Outcome {
    SUCCEEDED,
    RETRIES_EXHAUSTED,
    NON_RETRYABLE_FAILURE
  }

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
   * Builds a session's telemetry from its settings and pool MX bean, registering the pool gauges
   * and logging the (URL-redacted) "Session opened" line.
   */
  static Telemetry forSession(SessionSettings settings, HikariPoolMXBean poolMxBean) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(poolMxBean, "poolMxBean");

    Logger logger = LoggerFactory.getLogger(Telemetry.class);
    Tracer tracer =
        settings.openTelemetry().getTracer(settings.scopeName(), settings.scopeVersion());
    Meter meter = settings.openTelemetry().getMeter(settings.scopeName());
    DoubleHistogram durationHistogram =
        meter
            .histogramBuilder(METRIC_NAME)
            .setUnit("s")
            .setDescription("Duration of database client operations")
            // The SDK default boundaries are millisecond-scaled and collapse second-unit values
            // into one bucket, so the caller-configured boundaries are advised instead.
            .setExplicitBucketBoundariesAdvice(settings.durationHistogramBoundaries())
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

    List<ObservableLongGauge> gauges = registerPoolGauges(meter, poolMxBean, settings.poolName());

    logger.info(
        "Session opened for jdbcUrl={} user={} artifact={}",
        redactUrl(settings.jdbcUrl()),
        settings.user(),
        settings.artifactName());

    return new Telemetry(
        tracer,
        durationHistogram,
        statementRetries,
        transactionRetries,
        logger,
        settings.user(),
        settings.artifactName(),
        settings.slowQueryLogThreshold(),
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
    int passwordIndex = url.toLowerCase().indexOf(PASSWORD_PARAM);
    if (passwordIndex == -1) {
      return url;
    }
    int redactedStart = passwordIndex + PASSWORD_PARAM.length();
    int ampersandIndex = url.indexOf('&', passwordIndex);
    if (ampersandIndex == -1) {
      return url.substring(0, redactedStart) + "***";
    }
    return url.substring(0, redactedStart) + "***" + url.substring(ampersandIndex);
  }

  /**
   * Starts a span builder pre-seeded with the attributes present on every rich-pg span (db system,
   * artifact name) and parented to {@code parentSpan} when non-null.
   */
  private SpanBuilder newSpanBuilder(String name, SpanKind kind, Span parentSpan) {
    SpanBuilder builder =
        tracer
            .spanBuilder(name)
            .setSpanKind(kind)
            .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
            .setAttribute(ARTIFACT_NAME, artifactName);
    if (parentSpan != null) {
      builder.setParent(Context.current().with(parentSpan));
    }
    return builder;
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
        OPERATION_TYPE_STATEMENT,
        parentSpan);
  }

  /** Starts a batch CLIENT span (covers all attempts). */
  StatementHandle startBatch(StatementBatch<?> batch, Span parentSpan) {
    return startStatementSpan(
        batch.statementName(),
        batch.sql(),
        batch.operationName(),
        batch.collectionName(),
        batch.size(),
        OPERATION_TYPE_BATCH,
        parentSpan);
  }

  private StatementHandle startStatementSpan(
      String statementName,
      String sql,
      Optional<String> operationName,
      Optional<String> collectionName,
      Integer batchSize,
      String operationType,
      Span parentSpan) {
    var builder =
        newSpanBuilder(statementName, SpanKind.CLIENT, parentSpan)
            .setAttribute(DB_QUERY_TEXT, sql)
            .setAttribute(STATEMENT_NAME, statementName)
            .setAttribute(DB_USER, dbUser);
    operationName.ifPresent(v -> builder.setAttribute(DB_OPERATION_NAME, v));
    collectionName.ifPresent(v -> builder.setAttribute(DB_COLLECTION_NAME, v));
    if (batchSize != null) {
      builder.setAttribute(BATCH_SIZE, (long) batchSize);
    }
    return new StatementHandle(
        builder.startSpan(), statementName, sql, operationName, collectionName, operationType);
  }

  /** A started statement span plus what's needed to record its duration/outcome. */
  final class StatementHandle {
    private final Span span;
    private final String statementName;
    private final String sql;
    private final Optional<String> operationName;
    private final Optional<String> collectionName;
    private final String operationType;
    private final long startNanos = System.nanoTime();

    private StatementHandle(
        Span span,
        String statementName,
        String sql,
        Optional<String> operationName,
        Optional<String> collectionName,
        String operationType) {
      this.span = span;
      this.statementName = statementName;
      this.sql = sql;
      this.operationName = operationName;
      this.collectionName = collectionName;
      this.operationType = operationType;
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
      Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
      recordDuration(duration, durationAttributes());
      logIfSlow(statementName, duration);
      span.end();
    }

    private Attributes durationAttributes() {
      var attrs =
          Attributes.builder()
              .put(DB_SYSTEM_NAME, DB_SYSTEM)
              .put(DB_QUERY_TEXT, sql)
              .put(STATEMENT_NAME, statementName)
              .put(OPERATION_TYPE, operationType);
      operationName.ifPresent(v -> attrs.put(DB_OPERATION_NAME, v));
      collectionName.ifPresent(v -> attrs.put(DB_COLLECTION_NAME, v));
      return attrs.build();
    }
  }

  /**
   * Records a failed attempt as an exception span event on {@code operationSpan}, carrying the
   * exception, the attempt number, and the attempt duration.
   */
  void recordAttemptFailed(
      Span operationSpan, int attemptNumber, Throwable failure, Duration attemptDuration) {
    operationSpan.recordException(
        failure,
        Attributes.of(
            ATTEMPT_NUMBER,
            (long) attemptNumber,
            ATTEMPT_DURATION_SECONDS,
            attemptDuration.toNanos() / 1_000_000_000.0));
  }

  /** Starts the standalone-statement operation span, covering all attempts. */
  StatementOperationHandle startStatementOperation(
      Statement<?> statement, int maxAttempts, Span parentSpan) {
    var builder =
        newSpanBuilder(statement.statementName(), SpanKind.CLIENT, parentSpan)
            .setAttribute(DB_QUERY_TEXT, statement.sql())
            .setAttribute(STATEMENT_NAME, statement.statementName())
            .setAttribute(MAX_ATTEMPTS_STMT, (long) maxAttempts);
    return new StatementOperationHandle(builder.startSpan(), statement.statementName());
  }

  /** A started standalone-statement operation span plus what's needed to finish it. */
  final class StatementOperationHandle implements AutoCloseable {
    private final Span span;
    private final String statementName;
    private final long startNanos = System.nanoTime();
    private String errorType;

    private StatementOperationHandle(Span span, String statementName) {
      this.span = span;
      this.statementName = statementName;
    }

    Span span() {
      return span;
    }

    void finish(int attempts, Outcome outcome, Throwable failure) {
      String outcomeLabel = outcomeLabel(outcome, OUTCOME_SUCCEEDED);
      finishOperationAttributes(
          span,
          statementRetries,
          ATTEMPT_COUNT_STMT,
          OUTCOME_STMT,
          attempts,
          outcomeLabel,
          failure);
      errorType = errorTypeOf(failure);
      logIfExhausted(statementName, outcome, attempts, failure);
    }

    @Override
    public void close() {
      Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
      var attrs =
          Attributes.builder()
              .put(DB_SYSTEM_NAME, DB_SYSTEM)
              .put(STATEMENT_NAME, statementName)
              .put(OPERATION_TYPE, OPERATION_TYPE_STATEMENT);
      if (errorType != null) {
        attrs.put(ERROR_TYPE, errorType);
      }
      recordDuration(duration, attrs.build());
      logIfSlow(statementName, duration);
      span.end();
    }
  }

  /**
   * Starts the INTERNAL transaction operation span, covering all attempts.
   *
   * <p>When {@code transactionName} is non-null it becomes the span name and is recorded as the
   * {@code pgenie.transaction.name} attribute on both the span and the duration metric; otherwise
   * the span is named {@code "transaction"} and no name attribute is recorded.
   */
  TransactionOperationHandle startTransactionOperation(
      TransactionMode mode, String transactionName, int maxAttempts, Span parentSpan) {
    var builder =
        newSpanBuilder(
                transactionName != null ? transactionName : "transaction",
                SpanKind.INTERNAL,
                parentSpan)
            .setAttribute(ISOLATION_LEVEL, mode.isolationLevel().name())
            .setAttribute(READ_ONLY, mode.readOnly())
            .setAttribute(MAX_ATTEMPTS_TXN, (long) maxAttempts);
    if (transactionName != null) {
      builder.setAttribute(TRANSACTION_NAME, transactionName);
    }
    return new TransactionOperationHandle(builder.startSpan(), transactionName);
  }

  /**
   * A started transaction operation span plus what's needed to finish it. {@link #finish} records
   * outcome attributes; {@link #close} records the duration and ends the span. The executor
   * restores connection state between the two, inside the try-with-resources block, so the restore
   * stays within the span's duration.
   */
  final class TransactionOperationHandle implements AutoCloseable {
    private final Span span;
    private final String transactionName;
    private final long startNanos = System.nanoTime();
    private String errorType;

    private TransactionOperationHandle(Span span, String transactionName) {
      this.span = span;
      this.transactionName = transactionName;
    }

    Span span() {
      return span;
    }

    void finish(int attempts, Outcome outcome, Throwable failure) {
      String outcomeLabel = outcomeLabel(outcome, OUTCOME_COMMITTED);
      finishOperationAttributes(
          span,
          transactionRetries,
          ATTEMPT_COUNT_TXN,
          OUTCOME_TXN,
          attempts,
          outcomeLabel,
          failure);
      errorType = errorTypeOf(failure);
      logIfExhausted("Transaction", outcome, attempts, failure);
    }

    @Override
    public void close() {
      Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
      var attrs =
          Attributes.builder()
              .put(DB_SYSTEM_NAME, DB_SYSTEM)
              .put(OPERATION_TYPE, OPERATION_TYPE_TRANSACTION);
      if (transactionName != null) {
        attrs.put(TRANSACTION_NAME, transactionName);
      }
      if (errorType != null) {
        attrs.put(ERROR_TYPE, errorType);
      }
      recordDuration(duration, attrs.build());
      logIfSlow(transactionName != null ? transactionName : "transaction", duration);
      span.end();
    }
  }

  /** Starts the standalone-batch operation span, covering the batch's single attempt. */
  BatchOperationHandle startBatchOperation(StatementBatch<?> batch, Span parentSpan) {
    var builder =
        newSpanBuilder(batch.statementName(), SpanKind.CLIENT, parentSpan)
            .setAttribute(DB_QUERY_TEXT, batch.sql())
            .setAttribute(STATEMENT_NAME, batch.statementName())
            .setAttribute(BATCH_SIZE, (long) batch.size());
    batch.operationName().ifPresent(v -> builder.setAttribute(DB_OPERATION_NAME, v));
    batch.collectionName().ifPresent(v -> builder.setAttribute(DB_COLLECTION_NAME, v));
    return new BatchOperationHandle(builder.startSpan(), batch.statementName());
  }

  /**
   * A started standalone-batch operation span plus what's needed to finish it. Unlike {@link
   * StatementOperationHandle}/{@link TransactionOperationHandle}, a batch has no retry loop: it is
   * one attempt, so {@link #finish} takes the failure directly instead of an attempt count and
   * {@link Outcome}.
   */
  final class BatchOperationHandle implements AutoCloseable {
    private final Span span;
    private final String statementName;
    private final long startNanos = System.nanoTime();
    private String errorType;

    private BatchOperationHandle(Span span, String statementName) {
      this.span = span;
      this.statementName = statementName;
    }

    Span span() {
      return span;
    }

    /** Records the batch's single-attempt outcome. {@code failure} is {@code null} on success. */
    void finish(Throwable failure) {
      if (failure == null) {
        span.setStatus(StatusCode.OK);
      } else {
        span.recordException(failure);
        span.setStatus(StatusCode.ERROR, failure.getMessage());
      }
      errorType = errorTypeOf(failure);
    }

    @Override
    public void close() {
      Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
      var attrs =
          Attributes.builder()
              .put(DB_SYSTEM_NAME, DB_SYSTEM)
              .put(STATEMENT_NAME, statementName)
              .put(OPERATION_TYPE, OPERATION_TYPE_BATCH);
      if (errorType != null) {
        attrs.put(ERROR_TYPE, errorType);
      }
      recordDuration(duration, attrs.build());
      logIfSlow(statementName, duration);
      span.end();
    }
  }

  private static String outcomeLabel(Outcome outcome, String succeededLabel) {
    return switch (outcome) {
      case SUCCEEDED -> succeededLabel;
      case RETRIES_EXHAUSTED -> OUTCOME_RETRIES_EXHAUSTED;
      case NON_RETRYABLE_FAILURE -> OUTCOME_NON_RETRYABLE_FAILURE;
    };
  }

  /**
   * The {@code error.type} value for the operation duration histogram: {@code null} (attribute
   * omitted) on success, matching OTel semantic conventions; otherwise the failure's SQLSTATE, or
   * {@value #ERROR_TYPE_UNKNOWN} when the failure carries no SQLSTATE (e.g. a plain exception
   * thrown from a transaction body).
   */
  private static String errorTypeOf(Throwable failure) {
    if (failure == null) {
      return null;
    }
    String sqlState = new ClassifiedSqlFailure(failure).sqlState();
    return sqlState != null ? sqlState : ERROR_TYPE_UNKNOWN;
  }

  private void finishOperationAttributes(
      Span span,
      LongCounter retryCounter,
      AttributeKey<Long> attemptCountKey,
      AttributeKey<String> outcomeKey,
      int attempts,
      String outcomeLabel,
      Throwable failure) {
    span.setAttribute(attemptCountKey, (long) attempts);
    span.setAttribute(outcomeKey, outcomeLabel);
    retryCounter.add(Math.max(0, attempts - 1));
    if (failure == null) {
      span.setStatus(StatusCode.OK);
    } else {
      span.recordException(failure);
      span.setStatus(StatusCode.ERROR, failure.getMessage());
    }
  }

  private void logIfExhausted(
      String operationLabel, Outcome outcome, int attempts, Throwable failure) {
    if (outcome == Outcome.RETRIES_EXHAUSTED) {
      // failure.toString(), not the throwable itself: passing the throwable would dump a full
      // stack trace per exhaustion, which under sustained contention floods the console.
      logger.warn(
          "{} exhausted {} attempts, last failure: {}",
          operationLabel,
          attempts,
          String.valueOf(failure));
    }
  }

  private void recordDuration(Duration duration, Attributes attributes) {
    durationHistogram.record(duration.toNanos() / 1_000_000_000.0, attributes);
  }

  private void logIfSlow(String label, Duration duration) {
    if (duration.compareTo(slowQueryLogThreshold) > 0) {
      logger.warn(
          "Slow query detected: {} took {} seconds", label, duration.toNanos() / 1_000_000_000.0);
    }
  }

  Span startHealthCheckSpan() {
    return newSpanBuilder("healthCheck", SpanKind.CLIENT, null).startSpan();
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
          newSpanBuilder("session.close", SpanKind.INTERNAL, null)
              .setAttribute(CLOSE_CONNECTIONS_REMAINING, (long) remainingConnections)
              .startSpan();
      try {
        span.setStatus(
            remainingConnections > 0 ? StatusCode.ERROR : StatusCode.OK,
            remainingConnections > 0
                ? remainingConnections + " active connection(s) remained at close deadline"
                : null);
        if (remainingConnections > 0) {
          logger.warn("{} active connection(s) remained at close deadline", remainingConnections);
        }
      } finally {
        span.end();
      }
      logger.info("Session closed");
    }
  }
}
