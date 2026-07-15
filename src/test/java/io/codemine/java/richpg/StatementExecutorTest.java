package io.codemine.java.richpg;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.richpg.observability.StatementObservability;
import io.codemine.java.richpg.observability.StatementRetryObservability;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementExecutorTest {

  private InMemorySpanExporter spanExporter;
  private InMemoryMetricReader metricReader;
  private SdkTracerProvider tracerProvider;
  private SdkMeterProvider meterProvider;
  private OpenTelemetrySdk openTelemetry;
  private CollectingLogger logger;
  private StatementExecutor executor;

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

    executor =
        new StatementExecutor(
            new StatementRetryObservability(
                openTelemetry.getTracer("test"),
                openTelemetry.getMeter("test"),
                StatementObservability.buildDurationHistogram(openTelemetry.getMeter("test")),
                logger,
                "test-user",
                Duration.ofSeconds(1)));
  }

  @Test
  void succeedsOnFirstAttemptWithoutRetry() throws SQLException {
    List<String> closed = new ArrayList<>();
    Connection connection = fakeConnection("a", closed);
    ScriptedStatement statement = new ScriptedStatement(true);

    String result =
        executor.execute(statement, StatementSettings.DEFAULT, oneShot(connection), null);

    assertEquals("ok", result);
    assertEquals(List.of(connection), statement.connectionsUsed);
    assertEquals(List.of("a"), closed);

    flush();
    long retries = retriesCounterValue();
    assertEquals(0, retries);
  }

  @Test
  void idempotentStatementRetriesConnectionExceptionWithFreshConnection() throws SQLException {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    Connection connB = fakeConnection("b", closed);
    ScriptedStatement statement =
        new ScriptedStatement(true, new SQLException("connection failure", "08006"));
    Iterator<Connection> connections = List.of(connA, connB).iterator();

    String result = executor.execute(statement, StatementSettings.DEFAULT, connections::next, null);

    assertEquals("ok", result);
    assertEquals(List.of(connA, connB), statement.connectionsUsed);
    assertEquals(List.of("a", "b"), closed);

    flush();
    assertEquals(1, retriesCounterValue());
  }

  @Test
  void nonIdempotentStatementDoesNotRetryConnectionException() {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    SQLException failure = new SQLException("connection failure", "08006");
    ScriptedStatement statement = new ScriptedStatement(false, failure);

    SQLException thrown =
        assertThrows(
            SQLException.class,
            () -> executor.execute(statement, StatementSettings.DEFAULT, oneShot(connA), null));

    assertSame(failure, thrown);
    assertEquals(List.of(connA), statement.connectionsUsed);

    flush();
    assertEquals(0, retriesCounterValue());
    assertEquals(0, logger.warnings().size());
  }

  @Test
  void serializationFailureRetriesOnSameConnectionRegardlessOfIdempotency() throws SQLException {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    ScriptedStatement statement =
        new ScriptedStatement(false, new SQLException("serialization failure", "40001"));

    String result = executor.execute(statement, StatementSettings.DEFAULT, oneShot(connA), null);

    assertEquals("ok", result);
    assertEquals(List.of(connA, connA), statement.connectionsUsed);
    assertEquals(List.of("a"), closed);
  }

  @Test
  void deadlockDetectedRetriesOnSameConnection() throws SQLException {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    ScriptedStatement statement =
        new ScriptedStatement(false, new SQLException("deadlock detected", "40P01"));

    String result = executor.execute(statement, StatementSettings.DEFAULT, oneShot(connA), null);

    assertEquals("ok", result);
    assertEquals(List.of(connA, connA), statement.connectionsUsed);
  }

  @Test
  void exhaustsMaxAttemptsAndThrowsLastFailure() {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    SQLException failure1 = new SQLException("serialization failure 1", "40001");
    SQLException failure2 = new SQLException("serialization failure 2", "40001");
    ScriptedStatement statement = new ScriptedStatement(false, failure1, failure2);
    StatementSettings settings = new StatementSettings(2);

    SQLException thrown =
        assertThrows(
            SQLException.class, () -> executor.execute(statement, settings, oneShot(connA), null));

    assertSame(failure2, thrown);
    assertEquals(2, statement.connectionsUsed.size());

    flush();
    assertEquals(1, retriesCounterValue());
    assertEquals(1, logger.warnings().size());
  }

  @Test
  void nonRetryableFailureThrowsImmediately() {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    SQLException failure = new SQLException("syntax error", "42601");
    ScriptedStatement statement = new ScriptedStatement(true, failure);

    SQLException thrown =
        assertThrows(
            SQLException.class,
            () -> executor.execute(statement, StatementSettings.DEFAULT, oneShot(connA), null));

    assertSame(failure, thrown);
    assertEquals(List.of(connA), statement.connectionsUsed);

    flush();
    assertEquals(0, retriesCounterValue());
    assertEquals(0, logger.warnings().size());
  }

  @Test
  void retrySpanNestsOneLeafSpanPerAttempt() throws SQLException {
    List<String> closed = new ArrayList<>();
    Connection connA = fakeConnection("a", closed);
    Connection connB = fakeConnection("b", closed);
    ScriptedStatement statement =
        new ScriptedStatement(true, new SQLException("connection failure", "08006"));
    Iterator<Connection> connections = List.of(connA, connB).iterator();

    executor.execute(statement, StatementSettings.DEFAULT, connections::next, null);
    flush();

    List<SpanData> retrySpans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> "statement.retry".equals(span.getName()))
            .toList();
    assertEquals(1, retrySpans.size());
    SpanData retrySpan = retrySpans.get(0);

    List<SpanData> attemptSpans =
        spanExporter.getFinishedSpanItems().stream()
            .filter(span -> retrySpan.getSpanId().equals(span.getParentSpanId()))
            .toList();
    assertEquals(2, attemptSpans.size(), "expected one leaf span per attempt");
  }

  private static ConnectionSupplier oneShot(Connection connection) {
    AtomicBoolean used = new AtomicBoolean(false);
    return () -> {
      if (!used.compareAndSet(false, true)) {
        throw new SQLException("no more connections available");
      }
      return connection;
    };
  }

  private static Connection fakeConnection(String id, List<String> closedIds) {
    return (Connection)
        Proxy.newProxyInstance(
            StatementExecutorTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "close" -> {
                  closedIds.add(id);
                  return null;
                }
                case "toString" -> {
                  return "conn-" + id;
                }
                case "equals" -> {
                  return proxy == args[0];
                }
                case "hashCode" -> {
                  return System.identityHashCode(proxy);
                }
                default -> {
                  return null;
                }
              }
            });
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

  private static final class ScriptedStatement implements Statement<String> {
    private final Deque<SQLException> script;
    private final boolean idempotent;
    final List<Connection> connectionsUsed = new ArrayList<>();

    ScriptedStatement(boolean idempotent, SQLException... script) {
      this.idempotent = idempotent;
      this.script = new ArrayDeque<>(List.of(script));
    }

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
    public String decodeResultSet(ResultSet rs) {
      return "ok";
    }

    @Override
    public String decodeAffectedRows(long affectedRows) {
      return "ok";
    }

    @Override
    public boolean idempotent() {
      return idempotent;
    }

    @Override
    public String executeOn(Connection conn) throws SQLException {
      connectionsUsed.add(conn);
      SQLException next = script.poll();
      if (next != null) {
        throw next;
      }
      return "ok";
    }
  }
}
