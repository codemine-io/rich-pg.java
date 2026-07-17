package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StatementBatch}. */
class StatementBatchTest {

  @Test
  void constructorRejectsNullIterable() {
    var thrown = assertThrows(NullPointerException.class, () -> new StatementBatch<>(null));
    assertEquals("statements", thrown.getMessage());
  }

  @Test
  void constructorRejectsNullStatement() {
    ArrayList<Statement<Integer>> statements = new ArrayList<>();
    statements.add(new UpdateStatement(1, "uno"));
    statements.add(null);

    var thrown = assertThrows(NullPointerException.class, () -> new StatementBatch<>(statements));
    assertEquals("statement", thrown.getMessage());
  }

  @Test
  void constructorRejectsRowsReturningStatements() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new StatementBatch<>(List.of(new RowsReturningStatement())));

    assertEquals("Batch execution is only supported for update statements", thrown.getMessage());
  }

  @Test
  void constructorRejectsMismatchedSql() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new StatementBatch<>(
                    List.of(
                        new UpdateStatement(1, "uno"),
                        new UpdateStatement("UPDATE other_table SET value = ? WHERE id = ?"))));

    assertEquals("All batch statements must use the same SQL text", thrown.getMessage());
  }

  @Test
  void constructorRejectsMismatchedOperationName() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new StatementBatch<>(
                    List.of(
                        new UpdateStatement(1, "uno"),
                        new UpdateStatement(2, "dos", Optional.of("UPSERT"), Optional.empty()))));

    assertEquals("All batch statements must use the same operation name", thrown.getMessage());
  }

  @Test
  void constructorRejectsMismatchedCollectionName() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new StatementBatch<>(
                    List.of(
                        new UpdateStatement(1, "uno"),
                        new UpdateStatement(
                            2, "dos", Optional.empty(), Optional.of("other_table")))));

    assertEquals("All batch statements must use the same collection name", thrown.getMessage());
  }

  @Test
  void operationNameAndCollectionNameAreDerivedFromTheFirstStatement() {
    var batch =
        new StatementBatch<>(
            List.of(
                new UpdateStatement(1, "uno", Optional.of("UPDATE"), Optional.of("table")),
                new UpdateStatement(2, "dos", Optional.of("UPDATE"), Optional.of("table"))));

    assertEquals(Optional.of("UPDATE"), batch.operationName());
    assertEquals(Optional.of("table"), batch.collectionName());
  }

  @Test
  void emptyBatchHasEmptyOperationAndCollectionNames() {
    var batch = new StatementBatch<Integer>(List.of());

    assertEquals(Optional.empty(), batch.operationName());
    assertEquals(Optional.empty(), batch.collectionName());
  }

  private record UpdateStatement(
      int id,
      String value,
      String sql,
      Optional<String> operationName,
      Optional<String> collectionName)
      implements Statement<Integer> {

    UpdateStatement(int id, String value) {
      this(
          id, value, "UPDATE table SET value = ? WHERE id = ?", Optional.empty(), Optional.empty());
    }

    UpdateStatement(
        int id, String value, Optional<String> operationName, Optional<String> collectionName) {
      this(id, value, "UPDATE table SET value = ? WHERE id = ?", operationName, collectionName);
    }

    UpdateStatement(String sql) {
      this(2, "dos", sql, Optional.empty(), Optional.empty());
    }

    @Override
    public String sql() {
      return sql;
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
      return "SELECT id FROM table WHERE id = ?";
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
