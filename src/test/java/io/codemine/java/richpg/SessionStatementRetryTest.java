package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionStatementRetryTest {

  private InMemorySpanExporter exporter;
  private InMemoryMetricReader metricReader;
  private HikariDataSource dataSource;
  private SessionSettings settings;

  @BeforeEach
  void setUp() {
    exporter = InMemorySpanExporter.create();
    metricReader = InMemoryMetricReader.create();
    var otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build();
    HikariPoolMXBean pool = Mockito.mock(HikariPoolMXBean.class);
    dataSource = Mockito.mock(HikariDataSource.class);
    Mockito.when(dataSource.getHikariPoolMXBean()).thenReturn(pool);
    Mockito.doNothing().when(dataSource).close();
    settings = SessionSettings.defaults("jdbc:postgresql://h/db", "u", "p").withOpenTelemetry(otel);
  }

  /** The single {@code db.client.operation.duration} data point recorded for this test's call. */
  private HistogramPointData durationPoint() {
    return metricReader.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("db.client.operation.duration"))
        .findFirst()
        .orElseThrow()
        .getHistogramData()
        .getPoints()
        .iterator()
        .next();
  }

  private static Statement<String> statementReturning(String value, boolean idempotent) {
    Statement<String> s = Mockito.mock(Statement.class);
    Mockito.when(s.statementName()).thenReturn("selectThing");
    Mockito.when(s.sql()).thenReturn("select 1");
    Mockito.when(s.idempotent()).thenReturn(idempotent);
    Mockito.when(s.operationName()).thenReturn(java.util.Optional.empty());
    Mockito.when(s.collectionName()).thenReturn(java.util.Optional.empty());
    return s;
  }

  @Test
  void succeedsOnFirstAttempt() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Mockito.when(statement.execute(Mockito.any())).thenReturn("ok");
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(dataSource.getConnection()).thenReturn(connection);

    Session session = new Session(settings, dataSource);

    try {
      String result = session.execute(statement);
      assertThat(result).isEqualTo("ok");
    } finally {
      session.close();
    }

    var span = exporter.getFinishedSpanItems().get(0);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.statement.attempt_count")))
        .isEqualTo(1L);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("pgenie.statement.outcome")))
        .isEqualTo("succeeded");
    assertThat(span.getEvents()).isEmpty();
    assertThat(durationPoint().getAttributes().get(AttributeKey.stringKey("error.type"))).isNull();
  }

  @Test
  void retriesOnSerializationFailureSameConnection() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Mockito.when(statement.execute(Mockito.any()))
        .thenThrow(new SQLException("conflict", "40001"))
        .thenReturn("ok");
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(dataSource.getConnection()).thenReturn(connection);

    Session session = new Session(settings, dataSource);

    try {
      String result = session.execute(statement);
      assertThat(result).isEqualTo("ok");
    } finally {
      session.close();
    }

    // The same connection is used across attempts (same-connection retry strategy), and closed
    // exactly once in the executor's outer finally after all attempts complete.
    Mockito.verify(connection).close();
  }

  @Test
  void retriesOnConnectionExceptionWithFreshConnectionOnlyWhenIdempotent() throws SQLException {
    Statement<String> statement = statementReturning("ok", true);
    Connection failingConnection = Mockito.mock(Connection.class);
    Connection freshConnection = Mockito.mock(Connection.class);
    Mockito.when(statement.execute(failingConnection))
        .thenThrow(new SQLException("conn lost", "08006"));
    Mockito.when(statement.execute(freshConnection)).thenReturn("ok");
    Mockito.when(dataSource.getConnection()).thenReturn(failingConnection, freshConnection);

    Session session = new Session(settings, dataSource);

    try {
      String result = session.execute(statement);
      assertThat(result).isEqualTo("ok");
    } finally {
      session.close();
    }

    Mockito.verify(failingConnection).close();
  }

  @Test
  void nonIdempotentConnectionExceptionDoesNotRetry() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(statement.execute(connection)).thenThrow(new SQLException("conn lost", "08006"));
    Mockito.when(dataSource.getConnection()).thenReturn(connection);

    Session session = new Session(settings, dataSource);

    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);
    } finally {
      session.close();
    }

    var span = exporter.getFinishedSpanItems().get(0);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("pgenie.statement.outcome")))
        .isEqualTo("non_retryable_failure");
    assertThat(durationPoint().getAttributes().get(AttributeKey.stringKey("error.type")))
        .isEqualTo("08006");
  }

  @Test
  void exhaustsMaxAttempts() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(statement.execute(connection)).thenThrow(new SQLException("conflict", "40001"));
    Mockito.when(dataSource.getConnection()).thenReturn(connection);

    Session session = new Session(settings.withRetryAttempts(2), dataSource);

    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);
    } finally {
      session.close();
    }

    var span = exporter.getFinishedSpanItems().get(0);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.statement.attempt_count")))
        .isEqualTo(2L);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("pgenie.statement.outcome")))
        .isEqualTo("retries_exhausted");
    // 2 "exception" events: one from recordAttemptFailed for the failed first attempt (carrying
    // attempt.number/attempt.duration_seconds), plus one the operation handle adds via
    // Span.recordException when the final attempt also fails.
    assertThat(span.getEvents()).hasSize(2);
    assertThat(durationPoint().getAttributes().get(AttributeKey.stringKey("error.type")))
        .isEqualTo("40001");
  }
}
