package io.codemine.java.richpg;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionIT extends AbstractDatabaseIT {

  private static final AttributeKey<String> DB_SYSTEM_NAME_KEY =
      AttributeKey.stringKey("db.system.name");
  private static final AttributeKey<Long> ATTEMPT_COUNT_KEY =
      AttributeKey.longKey("pgenie.transaction.attempt_count");
  private static final AttributeKey<String> OUTCOME_KEY =
      AttributeKey.stringKey("pgenie.transaction.outcome");
  private static final AttributeKey<Long> STATEMENT_ATTEMPT_COUNT_KEY =
      AttributeKey.longKey("pgenie.statement.attempt_count");
  private static final AttributeKey<String> STATEMENT_OUTCOME_KEY =
      AttributeKey.stringKey("pgenie.statement.outcome");
  private static final AttributeKey<Long> CLOSE_CONNECTIONS_REMAINING_KEY =
      AttributeKey.longKey("pgenie.session.close.connections_remaining");
  private static final AttributeKey<String> ERROR_TYPE_KEY = AttributeKey.stringKey("error.type");

  private InMemorySpanExporter spanExporter;
  private InMemoryMetricReader metricReader;
  private SdkTracerProvider tracerProvider;
  private SdkMeterProvider meterProvider;
  private OpenTelemetrySdk openTelemetry;

  @BeforeEach
  void setUpTelemetry() {
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
  }

  @Test
  void executeEmitsStatementOperationSpanAndDurationMetric() throws SQLException {
    SessionSettings config = config();
    try (Session session = new Session(config)) {
      String result = session.execute(new SelectOneStatement());
      assertEquals("one", result);
    }

    flush();

    // A standalone statement's telemetry is a single CLIENT operation span covering all attempts;
    // there is no more per-attempt leaf span.
    SpanData span = singleSpanNamed("SelectOneStatement");
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("postgresql", span.getAttributes().get(DB_SYSTEM_NAME_KEY));
    assertEquals(1L, span.getAttributes().get(STATEMENT_ATTEMPT_COUNT_KEY));
    assertEquals("succeeded", span.getAttributes().get(STATEMENT_OUTCOME_KEY));
    assertEquals(0, childSpansOf(span).size(), "expected no nested statement span");

    assertFalse(
        durationMetricPoints().isEmpty(),
        "expected db.client.operation.duration to be recorded for the standalone statement");
  }

  @Test
  void executeTransactionEmitsTransactionSpanWithOutcomeAndNestedStatementSpan()
      throws SQLException {
    SessionSettings config = config();
    try (Session session = new Session(config)) {
      String result = session.executeTransaction(ctx -> ctx.execute(new SelectOneStatement()));
      assertEquals("one", result);
    }

    flush();

    SpanData transactionSpan = singleSpanNamed("transaction");
    assertEquals(SpanKind.INTERNAL, transactionSpan.getKind());
    assertEquals(StatusCode.OK, transactionSpan.getStatus().getStatusCode());
    assertEquals("postgresql", transactionSpan.getAttributes().get(DB_SYSTEM_NAME_KEY));
    assertEquals(1L, transactionSpan.getAttributes().get(ATTEMPT_COUNT_KEY));
    assertEquals("committed", transactionSpan.getAttributes().get(OUTCOME_KEY));

    List<SpanData> childSpans = childSpansOf(transactionSpan);
    assertEquals(1, childSpans.size(), "expected one nested statement span");
    assertEquals("SelectOneStatement", childSpans.get(0).getName());
    assertEquals(SpanKind.CLIENT, childSpans.get(0).getKind());

    assertFalse(
        durationMetricPoints().isEmpty(),
        "expected db.client.operation.duration to be recorded for the transaction");
    assertEquals(
        null, durationMetricErrorType("transaction"), "error.type must be absent on success");
  }

  @Test
  void healthCheckReturnsCachedEagerProbeResultAndEmitsHealthCheckSpan() {
    SessionSettings config = config();
    try (Session session = new Session(config)) {
      // The eager probe at session open already populated the cached state; with the default
      // 10-second period no further probe runs within this test.
      assertTrue(session.healthCheck());
    }

    flush();

    SpanData span = singleSpanNamed("healthCheck");
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("postgresql", span.getAttributes().get(DB_SYSTEM_NAME_KEY));
  }

  @Test
  void backgroundProbeRepeatsPerHealthCheckPeriodAndStopsOnClose() throws InterruptedException {
    SessionSettings config = config().withHealthCheckPeriod(java.time.Duration.ofMillis(100));
    Session session = new Session(config);
    try {
      long deadline = System.nanoTime() + SECONDS.toNanos(5);
      while (healthCheckSpanCount() < 3 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertTrue(healthCheckSpanCount() >= 3, "expected repeated background probes");
    } finally {
      session.close();
    }

    assertFalse(session.healthCheck(), "closed session must report unhealthy");
    long countAtClose = healthCheckSpanCount();
    Thread.sleep(300);
    assertEquals(countAtClose, healthCheckSpanCount(), "probe thread must stop on close");
  }

  private long healthCheckSpanCount() {
    tracerProvider.forceFlush().join(5, SECONDS);
    return spanExporter.getFinishedSpanItems().stream()
        .filter(span -> "healthCheck".equals(span.getName()))
        .count();
  }

  @Test
  void closeEmitsSessionCloseSpanAndUnregistersPoolGauges() {
    SessionSettings config = config();
    Session session = new Session(config);

    assertFalse(poolGaugeMetrics().isEmpty(), "pool gauges should be registered");

    session.close();
    flush();

    SpanData span = singleSpanNamed("session.close");
    assertEquals(SpanKind.INTERNAL, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(0L, span.getAttributes().get(CLOSE_CONNECTIONS_REMAINING_KEY));

    assertTrue(poolGaugeMetrics().isEmpty(), "pool gauges should be unregistered after close");
  }

  @Test
  void namedTransactionUsesNameAsSpanNameAndRecordsNameAttribute() throws SQLException {
    SessionSettings config = config();
    try (Session session = new Session(config)) {
      session.executeTransaction(
          "transfer",
          TransactionMode.SERIALIZABLE_WRITE,
          ctx -> ctx.execute(new SelectOneStatement()));
    }

    flush();

    SpanData span = singleSpanNamed("transfer");
    assertEquals(SpanKind.INTERNAL, span.getKind());
    assertEquals(
        "transfer", span.getAttributes().get(AttributeKey.stringKey("pgenie.transaction.name")));
    assertEquals("committed", span.getAttributes().get(OUTCOME_KEY));
  }

  @Test
  void transactionRetryProducesNewStatementSpansPerAttemptUnderOneTransactionSpan()
      throws SQLException {
    SessionSettings config = config();
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    try (Session session = new Session(config)) {
      session.executeTransaction(
          ctx -> {
            ctx.execute(new SelectOneStatement());
            if (calls.getAndIncrement() == 0) {
              throw new SQLException("conflict", "40001");
            }
            return null;
          });
    }

    flush();

    SpanData transactionSpan = singleSpanNamed("transaction");
    assertEquals(SpanKind.INTERNAL, transactionSpan.getKind());
    assertEquals(StatusCode.OK, transactionSpan.getStatus().getStatusCode());
    assertEquals(2L, transactionSpan.getAttributes().get(ATTEMPT_COUNT_KEY));
    assertEquals("committed", transactionSpan.getAttributes().get(OUTCOME_KEY));

    List<SpanData> childSpans = childSpansOf(transactionSpan);
    assertEquals(2, childSpans.size(), "expected one statement span per attempt, as siblings");
    for (SpanData child : childSpans) {
      assertEquals("SelectOneStatement", child.getName());
      assertEquals(SpanKind.CLIENT, child.getKind());
    }

    List<SpanData> allSpans = spanExporter.getFinishedSpanItems();
    long transactionSpanCount =
        allSpans.stream().filter(s -> "transaction".equals(s.getName())).count();
    long statementSpanCount =
        allSpans.stream()
            .filter(s -> SpanKind.CLIENT.equals(s.getKind()))
            // The eager background health probe at session open emits a CLIENT span too.
            .filter(s -> !"healthCheck".equals(s.getName()))
            .count();
    assertEquals(1, transactionSpanCount);
    assertEquals(2, statementSpanCount, "expected one CLIENT statement span per attempt");
  }

  @Test
  void transactionExhaustsAttemptsAndThrowsRetriesExhausted() {
    SessionSettings config = config().withRetryAttempts(2);
    try (Session session = new Session(config)) {
      SQLException thrown =
          org.junit.jupiter.api.Assertions.assertThrows(
              SQLException.class,
              () ->
                  session.executeTransaction(
                      ctx -> {
                        throw new SQLException("conflict", "40001");
                      }));
      assertEquals("40001", thrown.getSQLState());
    }

    flush();

    SpanData transactionSpan = singleSpanNamed("transaction");
    assertEquals(StatusCode.ERROR, transactionSpan.getStatus().getStatusCode());
    assertEquals(2L, transactionSpan.getAttributes().get(ATTEMPT_COUNT_KEY));
    assertEquals("retries_exhausted", transactionSpan.getAttributes().get(OUTCOME_KEY));
    assertEquals("retries_exhausted", durationMetricErrorType("transaction"));
  }

  @Test
  void transactionNonRetryableFailureDoesNotRetry() {
    SessionSettings config = config();
    try (Session session = new Session(config)) {
      SQLException thrown =
          org.junit.jupiter.api.Assertions.assertThrows(
              SQLException.class,
              () ->
                  session.executeTransaction(
                      ctx -> {
                        throw new SQLException("bad syntax", "42601");
                      }));
      assertEquals("42601", thrown.getSQLState());
    }

    flush();

    SpanData transactionSpan = singleSpanNamed("transaction");
    assertEquals(StatusCode.ERROR, transactionSpan.getStatus().getStatusCode());
    assertEquals(1L, transactionSpan.getAttributes().get(ATTEMPT_COUNT_KEY));
    assertEquals("non_retryable_failure", transactionSpan.getAttributes().get(OUTCOME_KEY));
    assertEquals("non_retryable_failure", durationMetricErrorType("transaction"));
  }

  private SessionSettings config() {
    return SessionSettings.defaults(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .withOpenTelemetry(openTelemetry);
  }

  private void flush() {
    tracerProvider.forceFlush().join(5, SECONDS);
    meterProvider.forceFlush().join(5, SECONDS);
  }

  private SpanData singleSpanNamed(String name) {
    List<SpanData> spans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> name.equals(span.getName()))
            .toList();
    assertEquals(1, spans.size(), spans::toString);
    return spans.get(0);
  }

  private List<SpanData> childSpansOf(SpanData parent) {
    String parentSpanId = parent.getSpanId();
    return spanExporter.getFinishedSpanItems().stream()
        .filter(span -> parentSpanId.equals(span.getParentSpanId()))
        .toList();
  }

  private List<MetricData> poolGaugeMetrics() {
    return metricReader.collectAllMetrics().stream()
        .filter(m -> m.getName().startsWith("pgenie.pool.connections."))
        .toList();
  }

  private List<MetricData> durationMetricPoints() {
    return metricReader.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("db.client.operation.duration"))
        .toList();
  }

  /**
   * The {@code error.type} attribute of the sole {@code db.client.operation.duration} point whose
   * {@code pgenie.operation.type} attribute matches {@code operationType} (a transaction span's
   * duration point coexists with its nested statements' duration points under the same metric
   * name).
   */
  private String durationMetricErrorType(String operationType) {
    AttributeKey<String> operationTypeKey = AttributeKey.stringKey("pgenie.operation.type");
    List<MetricData> points = durationMetricPoints();
    assertEquals(1, points.size(), points::toString);
    var histogramPoints =
        points.get(0).getHistogramData().getPoints().stream()
            .filter(p -> operationType.equals(p.getAttributes().get(operationTypeKey)))
            .toList();
    assertEquals(1, histogramPoints.size(), histogramPoints::toString);
    return histogramPoints.get(0).getAttributes().get(ERROR_TYPE_KEY);
  }

  private record SelectOneStatement() implements Statement<String> {
    @Override
    public String sql() {
      return "select 1";
    }

    @Override
    public void bindParams(PreparedStatement ps) {}

    @Override
    public boolean returnsRows() {
      return true;
    }

    @Override
    public String decodeResultSet(ResultSet rs) throws SQLException {
      rs.next();
      return rs.getString(1);
    }

    @Override
    public String decodeAffectedRows(long affectedRows) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<String> operationName() {
      return java.util.Optional.of("SELECT");
    }

    @Override
    public java.util.Optional<String> collectionName() {
      return java.util.Optional.of("system");
    }

    @Override
    public String execute(java.sql.Connection connection) {
      return "one";
    }
  }
}
