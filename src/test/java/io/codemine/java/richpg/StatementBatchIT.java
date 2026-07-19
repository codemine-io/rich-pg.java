package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Integration tests for {@link StatementBatch}'s database-dependent behavior. */
class StatementBatchIT extends AbstractDatabaseIT {

  private static final String TABLE_NAME = "statement_batch_it";

  @BeforeEach
  void createTable() throws SQLException {
    try (var conn = openConnection();
        var ps = conn.createStatement()) {
      ps.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
      ps.executeUpdate(
          "CREATE TABLE " + TABLE_NAME + " (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
      ps.executeUpdate(
          "INSERT INTO " + TABLE_NAME + " (id, value) VALUES (1, 'one'), (2, 'two'), (3, 'three')");
    }
  }

  @Test
  void executeAppliesUpdatesInOrder() throws Exception {
    var batch =
        new StatementBatch<>(
            List.of(
                new UpdateStatement(1, "uno"),
                new UpdateStatement(99, "missing"),
                new UpdateStatement(3, "tres")));

    try (var conn = openConnection()) {
      assertEquals(List.of(1, 0, 1), batch.execute(conn));
    }

    assertTableValue(1, "uno");
    assertTableValue(2, "two");
    assertTableValue(3, "tres");
  }

  @Test
  void constructorRejectsEmptyIterable() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new StatementBatch<>(List.<UpdateStatement>of()));
    assertEquals("Batch must not be empty", thrown.getMessage());
  }

  @Test
  void executeRejectsNullConnection() {
    var batch = new StatementBatch<>(List.of(new UpdateStatement(1, "uno")));

    var thrown = assertThrows(NullPointerException.class, () -> batch.execute(null));
    assertEquals("connection", thrown.getMessage());
  }

  @Test
  void executeAppliesPositiveQueryTimeoutToThePreparedStatement() throws Exception {
    var batch = new StatementBatch<>(List.of(new SleepStatement(1, 2.0)));

    try (var conn = openConnection()) {
      SQLException thrown = assertThrows(SQLException.class, () -> batch.execute(conn, 1));
      assertTrue(
          thrown.getMessage().toLowerCase().contains("cancel"),
          "Expected a statement-cancellation error, got: " + thrown.getMessage());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void executeWithNonPositiveTimeoutLeavesTheDriverDefaultInPlace(int queryTimeoutSeconds)
      throws Exception {
    var batch = new StatementBatch<>(List.of(new SleepStatement(1, 2.0)));

    try (var conn = openConnection()) {
      assertEquals(List.of(1), batch.execute(conn, queryTimeoutSeconds));
    }
  }

  @Test
  void executeWithoutATimeoutArgumentLeavesTheDriverDefaultInPlace() throws Exception {
    var batch = new StatementBatch<>(List.of(new SleepStatement(1, 2.0)));

    try (var conn = openConnection()) {
      assertEquals(List.of(1), batch.execute(conn));
    }
  }

  private void assertTableValue(int id, String expectedValue) throws SQLException {
    try (var conn = openConnection();
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

  /** An update statement whose execution blocks for {@code sleepSeconds} via {@code pg_sleep}. */
  private record SleepStatement(int id, double sleepSeconds) implements Statement<Integer> {

    @Override
    public String sql() {
      return "UPDATE " + TABLE_NAME + " SET value = value WHERE id = ? AND pg_sleep(?) IS NOT NULL";
    }

    @Override
    public void bindParams(PreparedStatement ps) throws SQLException {
      ps.setInt(1, id);
      ps.setDouble(2, sleepSeconds);
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
}
