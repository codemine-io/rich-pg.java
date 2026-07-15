package io.codemine.java.richpg.observability;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.richpg.StatementSettings;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Owns the OpenTelemetry instrumentation and retry classification for {@link
 * io.codemine.java.richpg.Session#executeRetryable}.
 *
 * <p>The class is responsible for:
 *
 * <ul>
 *   <li>Creating the single INTERNAL {@code "statement.retry"} span that parents a {@link
 *       StatementObservability} leaf for every attempt.
 *   <li>Classifying a failure, via {@link #classify}, into the connection strategy the retry loop
 *       should use for its next attempt.
 *   <li>Recording retries on the {@code pgenie.statement.retries} counter and the final outcome
 *       ({@code succeeded}, {@code retries_exhausted}, {@code non_retryable_failure}) on the span.
 * </ul>
 *
 * <p>Callers obtain a {@link StatementRetryObservation} via {@link #observe}, run their retry loop
 * against it, and call {@link StatementRetryObservation#markSucceeded} or {@link
 * StatementRetryObservation#markFailed} before closing the observation.
 */
public final class StatementRetryObservability {

  /** Name of the statement-retry span. */
  static final String SPAN_NAME = "statement.retry";

  /** Name of the statement-retries counter. */
  public static final String RETRIES_METRIC_NAME = "pgenie.statement.retries";

  /** Description of the statement-retries counter. */
  static final String RETRIES_METRIC_DESCRIPTION = "Number of standalone statement retries";

  /** {@code db.system.name} attribute key. */
  static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");

  /** {@code pgenie.statement.max_attempts} attribute key. */
  static final AttributeKey<Long> MAX_ATTEMPTS =
      AttributeKey.longKey("pgenie.statement.max_attempts");

  /** {@code pgenie.statement.attempt_count} attribute key. */
  static final AttributeKey<Long> ATTEMPT_COUNT =
      AttributeKey.longKey("pgenie.statement.attempt_count");

  /** {@code pgenie.statement.outcome} attribute key. */
  static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("pgenie.statement.outcome");

  /** Outcome value for a statement that succeeded, possibly after retries. */
  static final String OUTCOME_SUCCEEDED = "succeeded";

  /** Outcome value for a statement that exhausted all retry attempts. */
  static final String OUTCOME_RETRIES_EXHAUSTED = "retries_exhausted";

  /** Outcome value for a statement that failed with a non-retryable error. */
  static final String OUTCOME_NON_RETRYABLE_FAILURE = "non_retryable_failure";

  private static final String DB_SYSTEM = "postgresql";

  private final Tracer tracer;
  private final LongCounter retriesCounter;
  private final DoubleHistogram durationHistogram;
  private final Logger logger;
  private final String dbUser;
  private final Duration slowQueryLogThreshold;

  /**
   * Creates a new statement-retry observability helper.
   *
   * @param tracer the OpenTelemetry tracer used to create the retry span and the per-attempt
   *     statement spans
   * @param meter the OpenTelemetry meter used to derive the retries counter
   * @param durationHistogram the shared {@code db.client.operation.duration} histogram, from {@link
   *     StatementObservability#buildDurationHistogram}
   * @param logger the SLF4J logger used to warn when retries are exhausted
   * @param dbUser the database user to attach as {@code pgenie.db.user} on per-attempt statement
   *     spans
   * @param slowQueryLogThreshold queries running longer than this threshold are logged as slow;
   *     zero logs every query; must not be negative
   * @throws NullPointerException if any argument is null
   */
  public StatementRetryObservability(
      Tracer tracer,
      Meter meter,
      DoubleHistogram durationHistogram,
      Logger logger,
      String dbUser,
      Duration slowQueryLogThreshold) {
    this.tracer = Objects.requireNonNull(tracer, "tracer");
    Objects.requireNonNull(meter, "meter");
    this.durationHistogram = Objects.requireNonNull(durationHistogram, "durationHistogram");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.dbUser = Objects.requireNonNull(dbUser, "dbUser");
    this.slowQueryLogThreshold =
        Objects.requireNonNull(slowQueryLogThreshold, "slowQueryLogThreshold");
    this.retriesCounter =
        meter
            .counterBuilder(RETRIES_METRIC_NAME)
            .setDescription(RETRIES_METRIC_DESCRIPTION)
            .build();
  }

  /**
   * Starts a statement-retry observation for the given settings.
   *
   * @param settings the statement settings
   * @param parentSpan the parent span, or {@code null} to use the current span
   * @return a new statement-retry observation
   * @throws NullPointerException if {@code settings} is null
   */
  public StatementRetryObservation observe(StatementSettings settings, Span parentSpan) {
    Objects.requireNonNull(settings, "settings");
    return new StatementRetryObservation(startSpan(settings, parentSpan));
  }

  private Span startSpan(StatementSettings settings, Span parentSpan) {
    var builder =
        tracer
            .spanBuilder(SPAN_NAME)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
            .setAttribute(MAX_ATTEMPTS, (long) settings.maxAttempts());

    if (parentSpan != null) {
      builder.setParent(Context.current().with(parentSpan));
    }
    // When parentSpan is null, the span builder defaults to the current context,
    // making the span a child of Span.current().

    return builder.startSpan();
  }

  /** The connection strategy a retry loop should use for its next attempt. */
  public enum ConnectionStrategy {
    /** Retry on the same connection: the failure did not compromise the connection itself. */
    SAME_CONNECTION,
    /** Retry on a freshly borrowed connection: the failed connection may no longer be usable. */
    NEW_CONNECTION,
    /** Do not retry. */
    NO_RETRY
  }

  /**
   * Classifies {@code failure} into the connection strategy a retry loop should use.
   *
   * <p>SQLSTATE {@code 40001} (serialization failure) and {@code 40P01} (deadlock detected) are
   * retried on the same connection regardless of {@code idempotent}, since PostgreSQL guarantees
   * the failing statement's own implicit transaction did not commit. SQLSTATE class {@code 08}
   * (connection exception) is retried on a freshly borrowed connection, but only when {@code
   * idempotent} is {@code true}: the outcome is ambiguous, so retrying a non-idempotent statement
   * could duplicate an effect that already landed. Every other failure is not retried.
   *
   * @param failure the exception to classify; may be null
   * @param idempotent whether the statement being retried is idempotent
   * @return the connection strategy to use for the next attempt
   */
  public static ConnectionStrategy classify(Throwable failure, boolean idempotent) {
    if (failure == null) {
      return ConnectionStrategy.NO_RETRY;
    }
    SQLException sqlException = extractSqlException(failure);
    if (sqlException == null) {
      return ConnectionStrategy.NO_RETRY;
    }
    String state = sqlException.getSQLState();
    if (state == null) {
      return ConnectionStrategy.NO_RETRY;
    }
    if (state.equals("40001") || state.equals("40P01")) {
      return ConnectionStrategy.SAME_CONNECTION;
    }
    if (state.startsWith("08") && idempotent) {
      return ConnectionStrategy.NEW_CONNECTION;
    }
    return ConnectionStrategy.NO_RETRY;
  }

  private static SQLException extractSqlException(Throwable t) {
    if (t instanceof SQLException sqlException) {
      return sqlException;
    }
    Throwable cause = t.getCause();
    if (cause instanceof SQLException sqlException) {
      return sqlException;
    }
    return null;
  }

  /**
   * A single in-flight statement-retry observation.
   *
   * <p>Callers build a {@link StatementObservability} leaf per attempt via {@link
   * #forAttempt(Statement)}, parented to this observation's span.
   */
  public final class StatementRetryObservation implements AutoCloseable {

    private final Span span;
    private boolean closed;

    private StatementRetryObservation(Span span) {
      this.span = Objects.requireNonNull(span, "span");
    }

    /**
     * Returns the statement-retry span.
     *
     * @return the span representing this retry sequence
     */
    public Span span() {
      return span;
    }

    /**
     * Builds a {@link StatementObservability} leaf for one attempt, parented to this observation's
     * span.
     *
     * @param statement the statement about to be executed
     * @return a new statement observability, with its span already started
     */
    public StatementObservability forAttempt(Statement<?> statement) {
      return StatementObservability.forStatement(
          tracer, durationHistogram, logger, dbUser, slowQueryLogThreshold, statement, span);
    }

    /**
     * Marks the retry sequence as succeeded and records the outcome on the span.
     *
     * <p>Sets {@code pgenie.statement.attempt_count}, {@code pgenie.statement.outcome}, span status
     * to {@code OK}, and adds the retry count to the counter.
     *
     * @param attempts the total number of attempts made, including the successful one; at least 1
     */
    public void markSucceeded(int attempts) {
      span.setAttribute(ATTEMPT_COUNT, (long) attempts);
      span.setAttribute(OUTCOME, OUTCOME_SUCCEEDED);
      retriesCounter.add(Math.max(0, attempts - 1));
      span.setStatus(StatusCode.OK);
    }

    /**
     * Marks the retry sequence as failed and records the outcome on the span.
     *
     * <p>Sets {@code pgenie.statement.attempt_count}, {@code pgenie.statement.outcome}, records the
     * exception, sets span status to {@code ERROR}, adds the retry count to the counter, and logs a
     * warning when retries were exhausted.
     *
     * @param failure the failure that ended the retry sequence; must not be null
     * @param attempts the total number of attempts made; at least 1
     * @param retryable whether {@code failure}'s classification allowed retrying (i.e. the retry
     *     loop stopped because {@code attempts} reached the configured maximum, not because the
     *     failure was classified as {@link ConnectionStrategy#NO_RETRY})
     * @throws NullPointerException if {@code failure} is null
     */
    public void markFailed(Throwable failure, int attempts, boolean retryable) {
      Objects.requireNonNull(failure, "failure");
      String outcome = retryable ? OUTCOME_RETRIES_EXHAUSTED : OUTCOME_NON_RETRYABLE_FAILURE;
      span.setAttribute(ATTEMPT_COUNT, (long) attempts);
      span.setAttribute(OUTCOME, outcome);
      retriesCounter.add(Math.max(0, attempts - 1));
      span.recordException(failure);
      span.setStatus(StatusCode.ERROR, failure.getMessage());

      if (OUTCOME_RETRIES_EXHAUSTED.equals(outcome)) {
        logger.warn("Statement exhausted {} attempts, last failure: {}", attempts, failure);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      span.end();
    }
  }
}
