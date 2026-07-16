package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StatementExecutorTest {

  private InMemorySpanExporter exporter;
  private Telemetry telemetry;

  @BeforeEach
  void setUp() {
    exporter = InMemorySpanExporter.create();
    OpenTelemetry otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    HikariPoolMXBean pool = Mockito.mock(HikariPoolMXBean.class);
    telemetry =
        Telemetry.forSession(
            SessionSettings.defaults("jdbc:postgresql://h/db", "u", "p").withOpenTelemetry(otel),
            pool);
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

    String result =
        new StatementExecutor(telemetry).execute(statement, 3, () -> connection, Span.getInvalid());

    assertThat(result).isEqualTo("ok");
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
  }

  @Test
  void retriesOnSerializationFailureSameConnection() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Mockito.when(statement.execute(Mockito.any()))
        .thenThrow(new SQLException("conflict", "40001"))
        .thenReturn("ok");
    Connection connection = Mockito.mock(Connection.class);
    Deque<Connection> supplied = new ArrayDeque<>(java.util.List.of(connection));

    String result =
        new StatementExecutor(telemetry).execute(statement, 3, supplied::poll, Span.getInvalid());

    assertThat(result).isEqualTo("ok");
    // The same connection is reused across attempts (same-connection retry strategy), and closed
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
    Deque<Connection> supplied =
        new ArrayDeque<>(java.util.List.of(failingConnection, freshConnection));

    String result =
        new StatementExecutor(telemetry).execute(statement, 3, supplied::poll, Span.getInvalid());

    assertThat(result).isEqualTo("ok");
    Mockito.verify(failingConnection).close();
  }

  @Test
  void nonIdempotentConnectionExceptionDoesNotRetry() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(statement.execute(connection)).thenThrow(new SQLException("conn lost", "08006"));

    assertThatThrownBy(
            () ->
                new StatementExecutor(telemetry)
                    .execute(statement, 3, () -> connection, Span.getInvalid()))
        .isInstanceOf(SQLException.class);
    var span = exporter.getFinishedSpanItems().get(0);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("pgenie.statement.outcome")))
        .isEqualTo("non_retryable_failure");
  }

  @Test
  void exhaustsMaxAttempts() throws SQLException {
    Statement<String> statement = statementReturning("ok", false);
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(statement.execute(connection)).thenThrow(new SQLException("conflict", "40001"));

    assertThatThrownBy(
            () ->
                new StatementExecutor(telemetry)
                    .execute(statement, 2, () -> connection, Span.getInvalid()))
        .isInstanceOf(SQLException.class);
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
    // 2 events: the "attempt 1 failed" event from recordAttemptFailed, plus the automatic
    // "exception" event that Telemetry.finishStatementOperation adds via Span.recordException when
    // the final attempt also fails.
    assertThat(span.getEvents()).hasSize(2);
  }
}
