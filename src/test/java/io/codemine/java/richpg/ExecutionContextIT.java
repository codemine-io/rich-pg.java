package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/** Integration tests for {@link ExecutionContext} batch execution. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExecutionContextIT {

  private static final String TABLE_NAME = "execution_context_it";

  static final PostgreSQLContainer<?> container;
  static final HikariDataSource jdbcPool;

  static {
    container =
        new PostgreSQLContainer<>("postgres:18").withCommand("postgres -c max_connections=300");
    container.start();

    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(container.getJdbcUrl());
    hikariConfig.setUsername(container.getUsername());
    hikariConfig.setPassword(container.getPassword());
    hikariConfig.addDataSourceProperty("prepareThreshold", "0");
    hikariConfig.setMaximumPoolSize(10);
    jdbcPool = new HikariDataSource(hikariConfig);

    Runtime.getRuntime().addShutdownHook(new Thread(jdbcPool::close));
  }

  @SuppressWarnings("unused")
  @BeforeEach
  void resetTable() throws SQLException {
    try (var conn = jdbcPool.getConnection();
        var ps = conn.createStatement()) {
      ps.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
      ps.executeUpdate(
          "CREATE TABLE " + TABLE_NAME + " (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
      ps.executeUpdate(
          "INSERT INTO " + TABLE_NAME + " (id, value) VALUES (1, 'one'), (2, 'two'), (3, 'three')");
    }
  }

  @Test
  void executeBatchAppliesUpdatesInOrder() throws Exception {
    try (var conn = jdbcPool.getConnection()) {
      TransactionContext context = TransactionContext.of(conn);
      List<Integer> result =
          context.executeBatch(
              List.of(
                  new UpdateStatement(1, "uno"),
                  new UpdateStatement(99, "missing"),
                  new UpdateStatement(3, "tres")));

      assertEquals(List.of(1, 0, 1), result);
    }

    assertTableValue(1, "uno");
    assertTableValue(2, "two");
    assertTableValue(3, "tres");
  }

  @Test
  void executeBatchEmptyReturnsEmptyList() throws Exception {
    try (var conn = jdbcPool.getConnection()) {
      TransactionContext context = TransactionContext.of(conn);
      assertTrue(context.executeBatch(List.of()).isEmpty());
    }
  }

  @Test
  void executeBatchRejectsNullIterable() throws Exception {
    try (var conn = jdbcPool.getConnection()) {
      TransactionContext context = TransactionContext.of(conn);
      var thrown = assertThrows(NullPointerException.class, () -> context.executeBatch(null));
      assertEquals("statements", thrown.getMessage());
    }
  }

  @Test
  void executeBatchRejectsNullStatement() throws Exception {
    try (var conn = jdbcPool.getConnection()) {
      TransactionContext context = TransactionContext.of(conn);
      ArrayList<Statement<Integer>> statements = new ArrayList<>();
      statements.add(new UpdateStatement(1, "uno"));
      statements.add(null);

      var thrown = assertThrows(NullPointerException.class, () -> context.executeBatch(statements));
      assertEquals("statement", thrown.getMessage());
    }
  }

  @Test
  void executeBatchRejectsRowsReturningStatements() throws Exception {
    try (var conn = jdbcPool.getConnection()) {
      TransactionContext context = TransactionContext.of(conn);
      var thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> context.executeBatch(List.of(new RowsReturningStatement())));

      assertEquals("Batch execution is only supported for update statements", thrown.getMessage());
    }
  }

  @Test
  void executeBatchRejectsMismatchedSql() throws Exception {
    try (var conn = jdbcPool.getConnection()) {
      TransactionContext context = TransactionContext.of(conn);
      var thrown =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  context.executeBatch(
                      List.of(
                          new UpdateStatement(1, "uno"),
                          new Statement<Integer>() {
                            @Override
                            public String sql() {
                              return "UPDATE other_table SET value = ? WHERE id = ?";
                            }

                            @Override
                            public void bindParams(PreparedStatement ps) throws SQLException {
                              ps.setString(1, "dos");
                              ps.setInt(2, 2);
                            }

                            @Override
                            public boolean returnsRows() {
                              return false;
                            }

                            @Override
                            public Integer decodeResultSet(ResultSet rs) throws SQLException {
                              throw new UnsupportedOperationException("Not used");
                            }

                            @Override
                            public Integer decodeAffectedRows(long affectedRows) {
                              return Math.toIntExact(affectedRows);
                            }
                          })));

      assertEquals("All batch statements must use the same SQL text", thrown.getMessage());
    }
  }

  private static void assertTableValue(int id, String expectedValue) throws SQLException {
    try (var conn = jdbcPool.getConnection();
        var ps = conn.prepareStatement("SELECT value FROM " + TABLE_NAME + " WHERE id = ?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "Expected a row for id=" + id);
        assertEquals(expectedValue, rs.getString(1));
        assertTrue(!rs.next(), "Expected exactly one row for id=" + id);
      }
    }
  }

  private record UpdateStatement(int id, String value) implements Statement<Integer> {

    @Override
    public String sql() {
      return "UPDATE " + TABLE_NAME + " SET value = ? WHERE id = ?";
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
  }

  private record RowsReturningStatement() implements Statement<Integer> {

    @Override
    public String sql() {
      return "SELECT id FROM " + TABLE_NAME + " WHERE id = ?";
    }

    @Override
    public void bindParams(PreparedStatement ps) throws SQLException {
      ps.setInt(1, 1);
    }

    @Override
    public boolean returnsRows() {
      return true;
    }

    @Override
    public Integer decodeResultSet(ResultSet rs) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public Integer decodeAffectedRows(long affectedRows) {
      return Math.toIntExact(affectedRows);
    }
  }
}
