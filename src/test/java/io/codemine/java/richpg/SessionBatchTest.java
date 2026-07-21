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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link Session#executeBatch}, the standalone (non-transactional) batch entry
 * point.
 */
class SessionBatchTest {

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

  private static Statement<Integer> updateStatement() throws SQLException {
    Statement<Integer> s = Mockito.mock(Statement.class);
    Mockito.when(s.statementName()).thenReturn("updateThing");
    Mockito.when(s.sql()).thenReturn("update thing set x = ?");
    Mockito.when(s.returnsRows()).thenReturn(false);
    Mockito.when(s.operationName()).thenReturn(Optional.empty());
    Mockito.when(s.collectionName()).thenReturn(Optional.empty());
    Mockito.when(s.decodeAffectedRows(Mockito.anyLong()))
        .thenAnswer(invocation -> Math.toIntExact((long) invocation.getArgument(0)));
    return s;
  }

  @Test
  void executesBatchAndReturnsDecodedResultsInOrder() throws SQLException {
    Statement<Integer> statement1 = updateStatement();
    Statement<Integer> statement2 = updateStatement();
    Connection connection = Mockito.mock(Connection.class);
    PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
    Mockito.when(dataSource.getConnection()).thenReturn(connection);
    Mockito.when(connection.prepareStatement("update thing set x = ?"))
        .thenReturn(preparedStatement);
    Mockito.when(preparedStatement.executeBatch()).thenReturn(new int[] {1, 1});

    Session session = new Session(settings, dataSource);

    List<Integer> results;
    try {
      results = session.executeBatch(List.of(statement1, statement2));
    } finally {
      session.close();
    }

    assertThat(results).containsExactly(1, 1);
    Mockito.verify(preparedStatement, Mockito.times(2)).addBatch();
    Mockito.verify(connection).close();

    var span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getName()).isEqualTo("updateThing");
    assertThat(durationPoint().getAttributes().get(AttributeKey.stringKey("pgenie.operation.type")))
        .isEqualTo("batch");
    assertThat(durationPoint().getAttributes().get(AttributeKey.stringKey("error.type"))).isNull();
  }

  @Test
  void batchFailureUsesTheFailingStatementsSqlStateAsErrorType() throws SQLException {
    Statement<Integer> statement = updateStatement();
    Connection connection = Mockito.mock(Connection.class);
    PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
    Mockito.when(dataSource.getConnection()).thenReturn(connection);
    Mockito.when(connection.prepareStatement("update thing set x = ?"))
        .thenReturn(preparedStatement);
    Mockito.when(preparedStatement.executeBatch())
        .thenThrow(new SQLException("unique violation", "23505"));

    Session session = new Session(settings, dataSource);

    try {
      assertThatThrownBy(() -> session.executeBatch(List.of(statement)))
          .isInstanceOf(SQLException.class);
    } finally {
      session.close();
    }

    Mockito.verify(connection).close();
    var span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    assertThat(durationPoint().getAttributes().get(AttributeKey.stringKey("error.type")))
        .isEqualTo("23505");
  }
}
