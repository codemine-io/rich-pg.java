package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit tests for {@link NestedExecutionContext}'s batch span behavior. */
class NestedExecutionContextTest {

  private static Telemetry telemetryWith(InMemorySpanExporter exporter) {
    OpenTelemetry otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    SessionSettings settings =
        SessionSettings.defaults("jdbc:postgresql://localhost/test", "u", "p")
            .withOpenTelemetry(otel);
    return Telemetry.forSession(settings, Mockito.mock(HikariPoolMXBean.class));
  }

  private static ExecutionContext delegateReturning(
      List<? extends Statement<?>> seen, List<?> result) {
    return new ExecutionContext() {
      @Override
      public <R> R execute(Statement<R> statement) {
        throw new UnsupportedOperationException("Not used");
      }

      @SuppressWarnings("unchecked")
      @Override
      public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) {
        statements.forEach(s -> ((List<Statement<?>>) seen).add(s));
        return (List<R>) result;
      }

      @Override
      public Savepoint setSavepoint() {
        throw new UnsupportedOperationException("Not used");
      }

      @Override
      public void rollback(Savepoint savepoint) {}

      @Override
      public void releaseSavepoint(Savepoint savepoint) {}
    };
  }

  @Test
  void executeBatchRecordsSpanWithBatchSizeAndMetadata() throws SQLException {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    Telemetry telemetry = telemetryWith(exporter);
    List<Statement<?>> seen = new java.util.ArrayList<>();
    NestedExecutionContext context =
        new NestedExecutionContext(
            telemetry, delegateReturning(seen, List.of(1, 0)), Span.getInvalid());

    List<Integer> result =
        context.executeBatch(List.of(new UpdateStatement(1, "uno"), new UpdateStatement(2, "dos")));

    assertThat(result).containsExactly(1, 0);
    assertThat(seen).hasSize(2);

    var span =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("UpdateStatement"))
            .findFirst()
            .orElseThrow();
    assertThat(span.getAttributes().get(AttributeKey.longKey("db.operation.batch.size")))
        .isEqualTo(2L);
    assertThat(span.getAttributes().get(AttributeKey.stringKey("db.operation.name")))
        .isEqualTo("UPDATE");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("db.collection.name")))
        .isEqualTo("table");
    assertThat(span.getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.OK);
  }

  @Test
  void executeBatchRejectsEmptyIterable() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    Telemetry telemetry = telemetryWith(exporter);
    List<Statement<?>> seen = new java.util.ArrayList<>();
    NestedExecutionContext context =
        new NestedExecutionContext(
            telemetry, delegateReturning(seen, List.of()), Span.getInvalid());

    assertThatThrownBy(() -> context.executeBatch(List.<UpdateStatement>of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(seen).isEmpty();
    assertThat(exporter.getFinishedSpanItems()).isEmpty();
  }

  private record UpdateStatement(int id, String value) implements Statement<Integer> {

    @Override
    public String sql() {
      return "UPDATE table SET value = ? WHERE id = ?";
    }

    @Override
    public void bindParams(PreparedStatement ps) throws SQLException {
      ps.setString(1, value);
      ps.setInt(2, id);
    }

    @Override
    public boolean returnsRows() {
      return false;
    }

    @Override
    public Integer decodeResultSet(ResultSet rs) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public Integer decodeAffectedRows(long affectedRows) {
      return Math.toIntExact(affectedRows);
    }

    @Override
    public Optional<String> operationName() {
      return Optional.of("UPDATE");
    }

    @Override
    public Optional<String> collectionName() {
      return Optional.of("table");
    }
  }
}
