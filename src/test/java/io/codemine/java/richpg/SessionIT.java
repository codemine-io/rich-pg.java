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
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionIT extends AbstractDatabaseIT {

  private static final AttributeKey<String> DB_SYSTEM_NAME_KEY =
      AttributeKey.stringKey("db.system.name");
  private static final AttributeKey<String> STATEMENT_NAME_KEY =
      AttributeKey.stringKey("pgenie.statement.name");
  private static final AttributeKey<String> DB_OPERATION_NAME_KEY =
      AttributeKey.stringKey("db.operation.name");
  private static final AttributeKey<String> DB_COLLECTION_NAME_KEY =
      AttributeKey.stringKey("db.collection.name");
  private static final AttributeKey<String> DB_QUERY_TEXT_KEY =
      AttributeKey.stringKey("db.query.text");
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
  void executeEmitsStatementSpanAndDurationMetric() throws SQLException {
    RichPgConfig config = config();
    try (Session session = new Session(config)) {
      String result = session.execute(new SelectOneStatement());
      assertEquals("one", result);
    }

    flush();

    SpanData span = singleSpanNamed("SelectOneStatement");
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("postgresql", span.getAttributes().get(DB_SYSTEM_NAME_KEY));
    assertEquals("SelectOneStatement", span.getAttributes().get(STATEMENT_NAME_KEY));
    assertEquals("SELECT", span.getAttributes().get(DB_OPERATION_NAME_KEY));
    assertEquals("system", span.getAttributes().get(DB_COLLECTION_NAME_KEY));
    assertEquals("select 1", span.getAttributes().get(DB_QUERY_TEXT_KEY));

    HistogramPointData point = singleDurationPoint();
    assertEquals("SelectOneStatement", point.getAttributes().get(STATEMENT_NAME_KEY));
    assertEquals("SELECT", point.getAttributes().get(DB_OPERATION_NAME_KEY));
    assertEquals("system", point.getAttributes().get(DB_COLLECTION_NAME_KEY));
  }

  @Test
  void executeTransactionEmitsTransactionSpanWithOutcomeAndNestedStatementSpan()
      throws SQLException {
    RichPgConfig config = config();
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
  }

  @Test
  void executeRetryableEmitsRetrySpanWithNestedStatementSpanOnSuccess() throws SQLException {
    RichPgConfig config = config();
    try (Session session = new Session(config)) {
      String result = session.executeRetryable(new SelectOneStatement());
      assertEquals("one", result);
    }

    flush();

    SpanData retrySpan = singleSpanNamed("statement.retry");
    assertEquals(SpanKind.INTERNAL, retrySpan.getKind());
    assertEquals(StatusCode.OK, retrySpan.getStatus().getStatusCode());
    assertEquals("postgresql", retrySpan.getAttributes().get(DB_SYSTEM_NAME_KEY));
    assertEquals(1L, retrySpan.getAttributes().get(STATEMENT_ATTEMPT_COUNT_KEY));
    assertEquals("succeeded", retrySpan.getAttributes().get(STATEMENT_OUTCOME_KEY));

    List<SpanData> childSpans = childSpansOf(retrySpan);
    assertEquals(1, childSpans.size(), "expected one nested statement span");
    assertEquals("SelectOneStatement", childSpans.get(0).getName());
    assertEquals(SpanKind.CLIENT, childSpans.get(0).getKind());
  }

  @Test
  void healthCheckReturnsTrueAndEmitsHealthCheckSpan() {
    RichPgConfig config = config();
    try (Session session = new Session(config)) {
      assertTrue(session.healthCheck());
    }

    flush();

    SpanData span = singleSpanNamed("healthCheck");
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("postgresql", span.getAttributes().get(DB_SYSTEM_NAME_KEY));
  }

  @Test
  void closeEmitsSessionCloseSpanAndUnregistersPoolGauges() {
    RichPgConfig config = config();
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

  private RichPgConfig config() {
    return RichPgConfig.defaults(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
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

  private HistogramPointData singleDurationPoint() {
    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    MetricData metric =
        metrics.stream()
            .filter(m -> "db.client.operation.duration".equals(m.getName()))
            .findFirst()
            .orElseThrow(
                () -> new NoSuchElementException("Missing db.client.operation.duration metric"));
    Collection<HistogramPointData> points = metric.getHistogramData().getPoints();
    assertEquals(1, points.size(), points::toString);
    return points.iterator().next();
  }

  private List<MetricData> poolGaugeMetrics() {
    return metricReader.collectAllMetrics().stream()
        .filter(m -> m.getName().startsWith("pgenie.pool.connections."))
        .toList();
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
