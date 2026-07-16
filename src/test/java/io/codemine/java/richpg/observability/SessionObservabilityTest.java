package io.codemine.java.richpg.observability;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.richpg.RichPgConfig;
import io.codemine.java.richpg.transaction.TransactionSettings;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
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
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionObservabilityTest {

  private static final AttributeKey<String> POOL_NAME_KEY = AttributeKey.stringKey("pool.name");
  private static final AttributeKey<String> DB_SYSTEM_NAME_KEY =
      AttributeKey.stringKey("db.system.name");
  private static final AttributeKey<Long> CLOSE_CONNECTIONS_REMAINING_KEY =
      AttributeKey.longKey("pgenie.session.close.connections_remaining");

  private InMemorySpanExporter spanExporter;
  private InMemoryMetricReader metricReader;
  private SdkTracerProvider tracerProvider;
  private SdkMeterProvider meterProvider;
  private OpenTelemetrySdk openTelemetry;

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
  }

  @AfterEach
  void tearDown() {
    tracerProvider.close();
    meterProvider.close();
  }

  private RichPgConfig config() {
    return RichPgConfig.defaults(
            "jdbc:postgresql://localhost/test?password=secret", "alice", "secret")
        .withOpenTelemetry(openTelemetry)
        .withPoolName("test-pool")
        .withSlowQueryLogThreshold(Duration.ofSeconds(1));
  }

  @Test
  void fromConfigRegistersFourPoolGaugesWithValuesAndPoolNameAttribute() {
    SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(1, 2, 3, 4));

    var metrics = metricReader.collectAllMetrics();

    assertGauge(metrics, "pgenie.pool.connections.active", 1);
    assertGauge(metrics, "pgenie.pool.connections.idle", 2);
    assertGauge(metrics, "pgenie.pool.connections.pending", 3);
    assertGauge(metrics, "pgenie.pool.connections.total", 4);
  }

  @Test
  void startCloseUnregistersPoolGauges() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(5, 6, 7, 8));
    assertFalse(poolGaugeMetrics().isEmpty(), "pool gauges should be registered");

    observability.startClose();

    assertTrue(poolGaugeMetrics().isEmpty(), "pool gauges should be unregistered after startClose");
  }

  @Test
  void startCloseIsIdempotentForGaugeClosing() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(9, 10, 11, 12));

    observability.startClose();
    observability.startClose();

    assertTrue(poolGaugeMetrics().isEmpty());
  }

  @Test
  void closeHandleFinishEmitsSessionCloseSpanWithNoRemainingConnections() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));

    var close = observability.startClose();
    close.finish(0);
    flush();

    SpanData span = singleSpanNamed("session.close");
    assertEquals(SpanKind.INTERNAL, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(0L, span.getAttributes().get(CLOSE_CONNECTIONS_REMAINING_KEY));
  }

  @Test
  void closeHandleFinishEmitsErrorStatusWhenConnectionsRemain() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));

    var close = observability.startClose();
    close.finish(3);
    flush();

    SpanData span = singleSpanNamed("session.close");
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals(3L, span.getAttributes().get(CLOSE_CONNECTIONS_REMAINING_KEY));
  }

  @Test
  void startHealthCheckSpanProducesClientSpanWithDbSystemAttribute() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));

    Span span = observability.startHealthCheckSpan();
    span.end();
    flush();

    SpanData healthCheckSpan = singleSpanNamed("healthCheck");
    assertEquals(SpanKind.CLIENT, healthCheckSpan.getKind());
    assertEquals("postgresql", healthCheckSpan.getAttributes().get(DB_SYSTEM_NAME_KEY));
  }

  @Test
  void forStatementReturnsUsableStatementObservability() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));

    assertNotNull(observability.forStatement(new NoOpStatement()));
    assertNotNull(observability.forStatement(new NoOpStatement(), null));
  }

  @Test
  void forStatementDoesNotLoseTheSuppliedParentSpan() throws SQLException {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));
    Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();

    observability.forStatement(new NoOpStatement(), parent).execute(() -> "ok");
    parent.end();
    flush();

    SpanData statementSpan =
        spanExporter.getFinishedSpanItems().stream()
            .filter(s -> !"parent".equals(s.getName()))
            .findFirst()
            .orElseThrow();
    assertEquals(parent.getSpanContext().getSpanId(), statementSpan.getParentSpanId());
  }

  @Test
  void forTransactionDoesNotLoseTheSuppliedParentSpan() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));
    Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();

    var observation =
        observability
            .forTransaction(TransactionSettings.SERIALIZABLE_READ, parent)
            .observe(TransactionSettings.SERIALIZABLE_READ, noOpConnection(), null);
    observation.markCommitted();
    observation.close();
    parent.end();
    flush();

    SpanData transactionSpan = singleSpanNamed("transaction");
    assertEquals(parent.getSpanContext().getSpanId(), transactionSpan.getParentSpanId());
  }

  @Test
  void forTransactionRejectsNullSettings() {
    SessionObservability observability =
        SessionObservability.fromConfig(config(), new StubHikariPoolMXBean(0, 0, 0, 0));

    assertThrows(NullPointerException.class, () -> observability.forTransaction(null));
  }

  @Test
  void fromConfigRejectsNullArguments() {
    assertThrows(
        NullPointerException.class,
        () -> SessionObservability.fromConfig(null, new StubHikariPoolMXBean(0, 0, 0, 0)));
    assertThrows(NullPointerException.class, () -> SessionObservability.fromConfig(config(), null));
  }

  private void flush() {
    tracerProvider.forceFlush().join(5, SECONDS);
    meterProvider.forceFlush().join(5, SECONDS);
  }

  private static Connection noOpConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            SessionObservabilityTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> null);
  }

  private record NoOpStatement() implements Statement<String> {
    @Override
    public String sql() {
      return "select 1";
    }

    @Override
    public void bindParams(PreparedStatement preparedStatement) {}

    @Override
    public boolean returnsRows() {
      return false;
    }

    @Override
    public String decodeResultSet(ResultSet resultSet) {
      return "ok";
    }

    @Override
    public String decodeAffectedRows(long affectedRows) {
      return "ok";
    }
  }

  private SpanData singleSpanNamed(String name) {
    List<SpanData> spans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> name.equals(span.getName()))
            .toList();
    assertEquals(1, spans.size(), spans::toString);
    return spans.get(0);
  }

  private List<MetricData> poolGaugeMetrics() {
    return metricReader.collectAllMetrics().stream()
        .filter(m -> m.getName().startsWith("pgenie.pool.connections."))
        .toList();
  }

  private void assertGauge(Collection<MetricData> metrics, String name, long expectedValue) {
    var metric =
        metrics.stream()
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Missing metric: " + name));

    var points = metric.getLongGaugeData().getPoints();
    assertEquals(1, points.size(), "Expected exactly one point for " + name);

    var point = points.stream().findFirst().orElseThrow();
    assertEquals(expectedValue, point.getValue(), "Wrong value for " + name);
    assertEquals(
        "test-pool", point.getAttributes().get(POOL_NAME_KEY), "Wrong pool.name for " + name);
  }

  /** Hand-written stub of {@link HikariPoolMXBean}; no mocking library is used. */
  private static final class StubHikariPoolMXBean implements HikariPoolMXBean {

    private final int active;
    private final int idle;
    private final int pending;
    private final int total;

    StubHikariPoolMXBean(int active, int idle, int pending, int total) {
      this.active = active;
      this.idle = idle;
      this.pending = pending;
      this.total = total;
    }

    @Override
    public int getActiveConnections() {
      return active;
    }

    @Override
    public int getIdleConnections() {
      return idle;
    }

    @Override
    public int getTotalConnections() {
      return total;
    }

    @Override
    public int getThreadsAwaitingConnection() {
      return pending;
    }

    @Override
    public void softEvictConnections() {}

    @Override
    public void suspendPool() {}

    @Override
    public void resumePool() {}
  }
}
