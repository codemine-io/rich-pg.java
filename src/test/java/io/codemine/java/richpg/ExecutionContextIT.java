package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link ExecutionContext} batch execution against a real database.
 *
 * <p>Batch validation edge cases (null/empty input, mismatched SQL, rows-returning statements) are
 * covered at the unit level by {@link TransactionContextTest}, since that validation happens before
 * any connection is touched; this class only needs to verify the actual DB round-trip.
 */
class ExecutionContextIT extends AbstractDatabaseIT {

  private static final String TABLE_NAME = "execution_context_it";

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
  void executeBatchAppliesUpdatesInOrder() throws Exception {
    try (var conn = openConnection()) {
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
}
