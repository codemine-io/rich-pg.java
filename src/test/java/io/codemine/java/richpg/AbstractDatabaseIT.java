package io.codemine.java.richpg;

import com.zaxxer.hikari.HikariDataSource;
import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a live PostgreSQL database.
 *
 * <p>Provides a shared {@link PostgreSQLContainer} and helper methods to open connections and
 * manage a simple {@code retry_counter} table used by transaction tests.
 */
abstract class AbstractDatabaseIT {

  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:18").withCommand("postgres", "-c", "max_connections=200");

  @BeforeAll
  static void startContainer() {
    PG.start();
  }

  @BeforeEach
  void createCounterTable() throws SQLException {
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "create table if not exists retry_counter (id int primary key, value int not null)")) {
      ps.execute();
    }
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into retry_counter (id, value) values (1, 0) on conflict (id) do update set value = 0")) {
      ps.executeUpdate();
    }
  }

  @AfterEach
  void dropCounterTable() throws SQLException {
    try (Connection conn = openConnection();
        PreparedStatement ps = conn.prepareStatement("drop table if exists retry_counter")) {
      ps.execute();
    }
  }

  Connection openConnection() throws SQLException {
    return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }

  private HikariDataSource dataSource;

  /** Base {@link SessionSettings} pointed at the shared test container, no OpenTelemetry wiring. */
  protected SessionSettings settings() {
    return SessionSettings.defaults(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }

  /** A pooled {@link HikariDataSource} for the shared test container, lazily created per test. */
  protected HikariDataSource dataSource() {
    if (dataSource == null) {
      dataSource = settings().toHikariDataSource();
    }
    return dataSource;
  }

  @AfterEach
  void closeDataSource() {
    if (dataSource != null) {
      dataSource.close();
      dataSource = null;
    }
  }

  /** A fresh in-memory span exporter for a test's own {@link Telemetry} instance. */
  protected InMemorySpanExporter newExporter() {
    return InMemorySpanExporter.create();
  }

  /** A {@link Telemetry} wired to export spans to {@code exporter}, sharing this fixture's pool. */
  protected Telemetry telemetryWith(InMemorySpanExporter exporter) {
    OpenTelemetry otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    return Telemetry.forSession(
        settings().withOpenTelemetry(otel), dataSource().getHikariPoolMXBean());
  }

  /** A trivial {@code select 1} statement usable as a nested statement inside a transaction. */
  protected Statement<String> selectOneStatement() {
    return new SelectOneStatement();
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
    public String execute(Connection connection) {
      return "one";
    }
  }
}
