package io.codemine.java.richpg;

import java.sql.SQLException;

/**
 * Classifies whether a statement's SQL-execution failure is evidence of integrational drift: the
 * application's compiled-in expectations about the schema no longer match reality (SQLSTATE class
 * {@code 42} &mdash; undefined table/column, type mismatch). Independent of {@link
 * ClassifiedSqlFailure}, which answers a different question (retry-worthiness): {@code 23505}
 * (unique violation) is transaction-retryable but not drift, while {@code 42P01} (undefined table)
 * is drift but not retryable.
 *
 * <p>Decode-step failures (see {@link io.codemine.java.postgresql.jdbc.Statement#decodeResultSet}
 * /{@link io.codemine.java.postgresql.jdbc.Statement#decodeAffectedRows}) are not classified here:
 * any failure there is drift by construction, since decoding exists only to map a real result into
 * the statement's declared shape.
 */
final class IntegrationalDriftClassifier {

  private static final String SCHEMA_OR_TYPE_MISMATCH_CLASS = "42";

  private IntegrationalDriftClassifier() {}

  /**
   * Classifies a SQL-execution-step failure as integrational drift or not.
   *
   * @param failure the SQL-execution-step failure to classify
   * @return {@code true} if {@code failure}'s SQLSTATE is class {@code 42}
   */
  static boolean isExecutionDrift(SQLException failure) {
    String sqlState = failure.getSQLState();
    return sqlState != null && sqlState.startsWith(SCHEMA_OR_TYPE_MISMATCH_CLASS);
  }
}
