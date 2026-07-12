package io.codemine.java.richpg.observability;

import static io.codemine.java.richpg.observability.TransactionObservability.ATTEMPT_COUNT;
import static io.codemine.java.richpg.observability.TransactionObservability.OUTCOME;
import static io.codemine.java.richpg.observability.TransactionObservability.OUTCOME_COMMITTED;
import static io.codemine.java.richpg.observability.TransactionObservability.OUTCOME_NON_RETRYABLE_FAILURE;
import static io.codemine.java.richpg.observability.TransactionObservability.OUTCOME_RETRIES_EXHAUSTED;
import static io.codemine.java.richpg.observability.TransactionObservability.SPAN_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.TransactionSettings;
import io.codemine.java.richpg.CollectingLogger;
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
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionObservabilityTest {

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

  private TransactionObservability observability() {
    return new TransactionObservability(
        openTelemetry.getTracer("test"),
        openTelemetry.getMeter("test"),
        StatementObservability.buildDurationHistogram(openTelemetry.getMeter("test")),
        logger,
        "test-user",
        Duration.ofSeconds(1));
  }

  @Test
  void successfulTransactionAttemptCountIncludesFinalAttempt() {
    var observation =
        observability().observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), null);

    observation.markCommitted();
    observation.close();
    flush();

    SpanData span = singleTransactionSpan();
    assertEquals(1L, span.getAttributes().get(ATTEMPT_COUNT));
    assertEquals(OUTCOME_COMMITTED, span.getAttributes().get(OUTCOME));
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(0, retriesCounterValue());
  }

  @Test
  void successfulRetriedTransactionAddsOneToRollbackCount() throws SQLException {
    var observation =
        observability().observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), null);

    observation.rollback();
    observation.rollback();
    observation.markCommitted();
    observation.close();
    flush();

    SpanData span = singleTransactionSpan();
    assertEquals(3L, span.getAttributes().get(ATTEMPT_COUNT));
    assertEquals(2, retriesCounterValue());
  }

  @Test
  void failedTransactionWithNoRollbacksCountsOneAttempt() {
    var observation =
        observability().observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), null);

    observation.markFailed(new SQLException("serialization failure", "40001"));
    observation.close();
    flush();

    SpanData span = singleTransactionSpan();
    assertEquals(1L, span.getAttributes().get(ATTEMPT_COUNT));
    assertEquals(OUTCOME_RETRIES_EXHAUSTED, span.getAttributes().get(OUTCOME));
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals(1, logger.warnings().size());
  }

  @Test
  void failedTransactionDoesNotOvercountWhenCommitWasAttempted() throws SQLException {
    var observation =
        observability().observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), null);

    observation.rollback();
    observation.rollback();
    observation.commit();
    observation.markFailed(new SQLException("syntax error", "42601"));
    observation.close();
    flush();

    SpanData span = singleTransactionSpan();
    assertEquals(
        2L,
        span.getAttributes().get(ATTEMPT_COUNT),
        "commit attempt must not add to the rollback count");
    assertEquals(OUTCOME_NON_RETRYABLE_FAILURE, span.getAttributes().get(OUTCOME));
    assertEquals(
        0, logger.warnings().size(), "non-retryable failures should not be logged as warnings");
  }

  @Test
  void withParentSpanBindsDefaultParentWhenObserveOmitsIt() {
    var parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();

    var observation =
        observability()
            .withParentSpan(parent)
            .observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), null);
    observation.markCommitted();
    observation.close();
    parent.end();
    flush();

    SpanData span = singleTransactionSpan();
    assertEquals(parent.getSpanContext().getSpanId(), span.getParentSpanId());
  }

  @Test
  void explicitObserveParentSpanOverridesBoundParentSpan() {
    var bound = openTelemetry.getTracer("test").spanBuilder("bound").startSpan();
    var explicit = openTelemetry.getTracer("test").spanBuilder("explicit").startSpan();

    var observation =
        observability()
            .withParentSpan(bound)
            .observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), explicit);
    observation.markCommitted();
    observation.close();
    bound.end();
    explicit.end();
    flush();

    SpanData span = singleTransactionSpan();
    assertEquals(explicit.getSpanContext().getSpanId(), span.getParentSpanId());
  }

  @Test
  void isRetryableFailureReturnsFalseForNull() {
    assertFalse(TransactionObservability.isRetryableFailure(null));
  }

  @Test
  void isRetryableFailureReturnsFalseForNonSqlException() {
    assertFalse(TransactionObservability.isRetryableFailure(new RuntimeException("no sql cause")));
  }

  @Test
  void isRetryableFailureUnwrapsWrappedCause() {
    SQLException sqlException = new SQLException("serialization failure", "40001");
    RuntimeException wrapped = new RuntimeException("wrapper", sqlException);

    assertTrue(TransactionObservability.isRetryableFailure(wrapped));
  }

  private static Connection noOpConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            TransactionObservabilityTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> null);
  }

  private SpanData singleTransactionSpan() {
    List<SpanData> spans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> SPAN_NAME.equals(span.getName()))
            .toList();
    assertEquals(1, spans.size(), spans::toString);
    return spans.get(0);
  }

  private long retriesCounterValue() {
    for (MetricData metric : metricReader.collectAllMetrics()) {
      if (TransactionObservability.RETRIES_METRIC_NAME.equals(metric.getName())) {
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
