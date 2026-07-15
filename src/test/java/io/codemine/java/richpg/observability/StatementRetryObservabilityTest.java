package io.codemine.java.richpg.observability;

import static io.codemine.java.richpg.observability.StatementRetryObservability.ATTEMPT_COUNT;
import static io.codemine.java.richpg.observability.StatementRetryObservability.ConnectionStrategy.NEW_CONNECTION;
import static io.codemine.java.richpg.observability.StatementRetryObservability.ConnectionStrategy.NO_RETRY;
import static io.codemine.java.richpg.observability.StatementRetryObservability.ConnectionStrategy.SAME_CONNECTION;
import static io.codemine.java.richpg.observability.StatementRetryObservability.MAX_ATTEMPTS;
import static io.codemine.java.richpg.observability.StatementRetryObservability.OUTCOME;
import static io.codemine.java.richpg.observability.StatementRetryObservability.OUTCOME_NON_RETRYABLE_FAILURE;
import static io.codemine.java.richpg.observability.StatementRetryObservability.OUTCOME_RETRIES_EXHAUSTED;
import static io.codemine.java.richpg.observability.StatementRetryObservability.OUTCOME_SUCCEEDED;
import static io.codemine.java.richpg.observability.StatementRetryObservability.SPAN_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.codemine.java.richpg.CollectingLogger;
import io.codemine.java.richpg.StatementSettings;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementRetryObservabilityTest {

  private InMemorySpanExporter spanExporter;
  private InMemoryMetricReader metricReader;
  private SdkTracerProvider tracerProvider;
  private SdkMeterProvider meterProvider;
  private OpenTelemetrySdk openTelemetry;
  private CollectingLogger logger;

  @BeforeEach
  void setUp() {
    spanExporter = InMemorySpanExporter.create();
    metricReader = InMemoryMetricReader.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
    meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build();
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();
    logger = new CollectingLogger();
  }

  private StatementRetryObservability observability() {
    return new StatementRetryObservability(
        openTelemetry.getTracer("test"),
        openTelemetry.getMeter("test"),
        StatementObservability.buildDurationHistogram(openTelemetry.getMeter("test")),
        logger,
        "test-user",
        Duration.ofSeconds(1));
  }

  @Test
  void classifyReturnsNewConnectionForConnectionExceptionWhenIdempotent() {
    assertEquals(
        NEW_CONNECTION,
        StatementRetryObservability.classify(
            new SQLException("connection failure", "08006"), true));
  }

  @Test
  void classifyReturnsNoRetryForConnectionExceptionWhenNotIdempotent() {
    assertEquals(
        NO_RETRY,
        StatementRetryObservability.classify(
            new SQLException("connection failure", "08006"), false));
  }

  @Test
  void classifyReturnsSameConnectionForSerializationFailureRegardlessOfIdempotency() {
    assertEquals(
        SAME_CONNECTION,
        StatementRetryObservability.classify(
            new SQLException("serialization failure", "40001"), false));
    assertEquals(
        SAME_CONNECTION,
        StatementRetryObservability.classify(
            new SQLException("serialization failure", "40001"), true));
  }

  @Test
  void classifyReturnsSameConnectionForDeadlockDetectedRegardlessOfIdempotency() {
    assertEquals(
        SAME_CONNECTION,
        StatementRetryObservability.classify(
            new SQLException("deadlock detected", "40P01"), false));
  }

  @Test
  void classifyReturnsNoRetryForUniqueViolation() {
    assertEquals(
        NO_RETRY,
        StatementRetryObservability.classify(new SQLException("unique violation", "23505"), true));
  }

  @Test
  void classifyReturnsNoRetryForNonSqlException() {
    assertEquals(
        NO_RETRY, StatementRetryObservability.classify(new RuntimeException("boom"), true));
  }

  @Test
  void classifyReturnsNoRetryForNullFailure() {
    assertEquals(NO_RETRY, StatementRetryObservability.classify(null, true));
  }

  @Test
  void succeededObservationCountsFinalAttempt() {
    var observation = observability().observe(StatementSettings.DEFAULT, null);

    observation.markSucceeded(1);
    observation.close();
    flush();

    SpanData span = singleRetrySpan();
    assertEquals(1L, span.getAttributes().get(ATTEMPT_COUNT));
    assertEquals(7L, span.getAttributes().get(MAX_ATTEMPTS));
    assertEquals(OUTCOME_SUCCEEDED, span.getAttributes().get(OUTCOME));
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(0, retriesCounterValue());
  }

  @Test
  void succeededObservationAfterRetriesAddsRetriesToCounter() {
    var observation = observability().observe(StatementSettings.DEFAULT, null);

    observation.markSucceeded(3);
    observation.close();
    flush();

    SpanData span = singleRetrySpan();
    assertEquals(3L, span.getAttributes().get(ATTEMPT_COUNT));
    assertEquals(2, retriesCounterValue());
  }

  @Test
  void failedObservationWithRetryableFailureReportsRetriesExhausted() {
    var observation = observability().observe(StatementSettings.DEFAULT, null);

    observation.markFailed(new SQLException("connection failure", "08006"), 7, true);
    observation.close();
    flush();

    SpanData span = singleRetrySpan();
    assertEquals(7L, span.getAttributes().get(ATTEMPT_COUNT));
    assertEquals(OUTCOME_RETRIES_EXHAUSTED, span.getAttributes().get(OUTCOME));
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals(1, logger.warnings().size());
    assertEquals(6, retriesCounterValue());
  }

  @Test
  void failedObservationWithNonRetryableFailureDoesNotWarn() {
    var observation = observability().observe(StatementSettings.DEFAULT, null);

    observation.markFailed(new SQLException("syntax error", "42601"), 1, false);
    observation.close();
    flush();

    SpanData span = singleRetrySpan();
    assertEquals(OUTCOME_NON_RETRYABLE_FAILURE, span.getAttributes().get(OUTCOME));
    assertEquals(0, logger.warnings().size());
    assertEquals(0, retriesCounterValue());
  }

  private SpanData singleRetrySpan() {
    List<SpanData> spans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> SPAN_NAME.equals(span.getName()))
            .toList();
    assertEquals(1, spans.size(), spans::toString);
    return spans.get(0);
  }

  private long retriesCounterValue() {
    for (MetricData metric : metricReader.collectAllMetrics()) {
      if (StatementRetryObservability.RETRIES_METRIC_NAME.equals(metric.getName())) {
        Collection<LongPointData> points = metric.getLongSumData().getPoints();
        long total = 0;
        for (LongPointData point : points) {
          total += point.getValue();
        }
        return total;
      }
    }
    return 0;
  }

  private void flush() {
    tracerProvider.forceFlush().join(5, SECONDS);
    meterProvider.forceFlush().join(5, SECONDS);
  }
}
