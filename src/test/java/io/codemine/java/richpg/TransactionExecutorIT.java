package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransactionExecutorIT extends AbstractDatabaseIT {

  @Test
  void commitsOnSuccess() throws SQLException {
    InMemorySpanExporter exporter = newExporter();
    Telemetry telemetry = telemetryWith(exporter);
    try (Connection connection = dataSource().getConnection()) {
      String result =
          new TransactionExecutor(telemetry)
              .execute(
                  ctx -> "ok",
                  TransactionSettings.SERIALIZABLE_WRITE,
                  3,
                  connection,
                  Span.getInvalid());
      assertThat(result).isEqualTo("ok");
    }
    var span =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("transaction"))
            .findFirst()
            .orElseThrow();
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.transaction.attempt_count")))
        .isEqualTo(1L);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                        "pgenie.transaction.outcome")))
        .isEqualTo("committed");
  }

  @Test
  void retriesOnSerializationFailureAndCountsAttempts() throws SQLException {
    InMemorySpanExporter exporter = newExporter();
    Telemetry telemetry = telemetryWith(exporter);
    AtomicInteger calls = new AtomicInteger();
    Transaction<String> flaky =
        ctx -> {
          if (calls.getAndIncrement() == 0) {
            throw new SQLException("conflict", "40001");
          }
          return "ok";
        };
    try (Connection connection = dataSource().getConnection()) {
      String result =
          new TransactionExecutor(telemetry)
              .execute(
                  flaky, TransactionSettings.SERIALIZABLE_WRITE, 3, connection, Span.getInvalid());
      assertThat(result).isEqualTo("ok");
    }
    var span =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("transaction"))
            .findFirst()
            .orElseThrow();
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.transaction.attempt_count")))
        .isEqualTo(2L);
    assertThat(span.getEvents()).hasSize(1);
  }

  @Test
  void exhaustsAttemptsAndThrows() throws SQLException {
    InMemorySpanExporter exporter = newExporter();
    Telemetry telemetry = telemetryWith(exporter);
    Transaction<String> alwaysConflicts =
        ctx -> {
          throw new SQLException("conflict", "40001");
        };
    try (Connection connection = dataSource().getConnection()) {
      assertThatThrownBy(
              () ->
                  new TransactionExecutor(telemetry)
                      .execute(
                          alwaysConflicts,
                          TransactionSettings.SERIALIZABLE_WRITE,
                          2,
                          connection,
                          Span.getInvalid()))
          .isInstanceOf(SQLException.class);
    }
    var span =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("transaction"))
            .findFirst()
            .orElseThrow();
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.transaction.attempt_count")))
        .isEqualTo(2L);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                        "pgenie.transaction.outcome")))
        .isEqualTo("retries_exhausted");
  }

  @Test
  void nonRetryableFailureRollsBackAndDoesNotRetry() throws SQLException {
    InMemorySpanExporter exporter = newExporter();
    Telemetry telemetry = telemetryWith(exporter);
    Transaction<String> syntaxError =
        ctx -> {
          throw new SQLException("bad syntax", "42601");
        };
    try (Connection connection = dataSource().getConnection()) {
      assertThatThrownBy(
              () ->
                  new TransactionExecutor(telemetry)
                      .execute(
                          syntaxError,
                          TransactionSettings.SERIALIZABLE_WRITE,
                          5,
                          connection,
                          Span.getInvalid()))
          .isInstanceOf(SQLException.class);
    }
    var span =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("transaction"))
            .findFirst()
            .orElseThrow();
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.transaction.attempt_count")))
        .isEqualTo(1L);
    assertThat(
            span.getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                        "pgenie.transaction.outcome")))
        .isEqualTo("non_retryable_failure");
  }

  @Test
  void statementsInsideTransactionGetOneSpanPerAttempt() throws SQLException {
    InMemorySpanExporter exporter = newExporter();
    Telemetry telemetry = telemetryWith(exporter);
    try (Connection connection = dataSource().getConnection()) {
      new TransactionExecutor(telemetry)
          .execute(
              ctx -> ctx.execute(selectOneStatement()),
              TransactionSettings.SERIALIZABLE_WRITE,
              3,
              connection,
              Span.getInvalid());
    }
    long statementSpans =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> !s.getName().equals("transaction"))
            .count();
    assertThat(statementSpans).isEqualTo(1);
  }
}
