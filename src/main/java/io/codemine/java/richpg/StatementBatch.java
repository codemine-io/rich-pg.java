package io.codemine.java.richpg;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Helper for executing statements as a single JDBC batch. All statements must share the same SQL
 * text, operation name, and collection name, and must not return rows.
 */
final class StatementBatch<R> {
  private final List<Statement<R>> statements;
  private final String sql;
  private final Optional<String> operationName;
  private final Optional<String> collectionName;

  /**
   * Create a batch of statements to execute together. All statements must be of the same type (i.e.
   * have the same SQL text, operation name, and collection name) and must not return rows.
   *
   * @param statements the statements to execute in batch
   */
  StatementBatch(Iterable<? extends Statement<R>> statements) {
    Objects.requireNonNull(statements, "statements");

    List<Statement<R>> batch = new ArrayList<>();
    String batchSql = null;
    Optional<String> batchOperationName = null;
    Optional<String> batchCollectionName = null;
    for (Statement<R> statement : statements) {
      Statement<R> batchStatement = Objects.requireNonNull(statement, "statement");
      if (batchStatement.returnsRows()) {
        throw new IllegalArgumentException(
            "Batch execution is only supported for update statements");
      }

      String statementSql = Objects.requireNonNull(batchStatement.sql(), "sql");
      Optional<String> statementOperationName = batchStatement.operationName();
      Optional<String> statementCollectionName = batchStatement.collectionName();
      if (batchSql == null) {
        batchSql = statementSql;
        batchOperationName = statementOperationName;
        batchCollectionName = statementCollectionName;
      } else {
        if (!batchSql.equals(statementSql)) {
          throw new IllegalArgumentException("All batch statements must use the same SQL text");
        }
        if (!batchOperationName.equals(statementOperationName)) {
          throw new IllegalArgumentException(
              "All batch statements must use the same operation name");
        }
        if (!batchCollectionName.equals(statementCollectionName)) {
          throw new IllegalArgumentException(
              "All batch statements must use the same collection name");
        }
      }

      batch.add(batchStatement);
    }

    this.statements = batch;
    this.sql = batchSql;
    this.operationName = batchOperationName == null ? Optional.empty() : batchOperationName;
    this.collectionName = batchCollectionName == null ? Optional.empty() : batchCollectionName;
  }

  /**
   * The shared SQL text of the batch, or {@code null} if the batch is empty.
   *
   * @return the shared SQL text of the batch, or {@code null} if empty
   */
  String sql() {
    return sql;
  }

  /**
   * The shared operation name of the batch, or empty if the batch is empty.
   *
   * @return the shared operation name of the batch
   */
  Optional<String> operationName() {
    return operationName;
  }

  /**
   * The shared collection name of the batch, or empty if the batch is empty.
   *
   * @return the shared collection name of the batch
   */
  Optional<String> collectionName() {
    return collectionName;
  }

  /**
   * The number of statements in the batch.
   *
   * @return the number of statements in the batch
   */
  int size() {
    return statements.size();
  }

  /**
   * The statements in the batch, in execution order.
   *
   * @return the statements in the batch
   */
  List<Statement<R>> statements() {
    return statements;
  }

  /**
   * Execute the batch of statements using the provided JDBC connection. Returns a list of decoded
   * affected-row results, in the same order as the input statements.
   *
   * @param connection the JDBC connection to use for batch execution
   * @return a list of decoded results corresponding to each statement in the batch
   * @throws SQLException if a database access error occurs during execution
   */
  List<R> execute(Connection connection) throws SQLException {
    return execute(connection, 0);
  }

  /**
   * Execute the batch of statements using the provided JDBC connection, bounding each attempt by
   * the given query timeout. Returns a list of decoded affected-row results, in the same order as
   * the input statements.
   *
   * @param connection the JDBC connection to use for batch execution
   * @param queryTimeoutSeconds the query timeout to apply, in seconds; values less than or equal to
   *     0 leave the driver's default in place
   * @return a list of decoded results corresponding to each statement in the batch
   * @throws SQLException if a database access error occurs during execution
   */
  List<R> execute(Connection connection, int queryTimeoutSeconds) throws SQLException {
    Objects.requireNonNull(connection, "connection");

    if (statements.isEmpty()) {
      return List.of();
    }

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      if (queryTimeoutSeconds > 0) {
        ps.setQueryTimeout(queryTimeoutSeconds);
      }
      for (Statement<R> statement : statements) {
        ps.clearParameters();
        statement.bindParams(ps);
        ps.addBatch();
      }

      int[] affectedRows = ps.executeBatch();
      List<R> results = new ArrayList<>(affectedRows.length);
      for (int index = 0; index < affectedRows.length; index++) {
        results.add(statements.get(index).decodeAffectedRows(affectedRows[index]));
      }
      return results;
    }
  }
}
