package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import io.codemine.java.richpg.SqlStateClassifier.RetryStrategy;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlStateClassifierTest {

  @Test
  void nonSqlExceptionIsNoRetry() {
    assertThat(SqlStateClassifier.classify(new RuntimeException("boom"), true))
        .isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void serializationFailureIsSameConnectionRegardlessOfIdempotency() {
    SQLException e = new SQLException("conflict", "40001");
    assertThat(SqlStateClassifier.classify(e, false)).isEqualTo(RetryStrategy.SAME_CONNECTION);
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.SAME_CONNECTION);
  }

  @Test
  void deadlockDetectedIsSameConnectionRegardlessOfIdempotency() {
    SQLException e = new SQLException("deadlock", "40P01");
    assertThat(SqlStateClassifier.classify(e, false)).isEqualTo(RetryStrategy.SAME_CONNECTION);
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.SAME_CONNECTION);
  }

  @Test
  void uniqueViolationIsNoRetryForTheStatementLoop() {
    SQLException e = new SQLException("dup", "23505");
    assertThat(SqlStateClassifier.classify(e, false)).isEqualTo(RetryStrategy.NO_RETRY);
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void connectionExceptionClassIsNewConnectionOnlyWhenIdempotent() {
    SQLException e = new SQLException("conn lost", "08006");
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.NEW_CONNECTION);
    assertThat(SqlStateClassifier.classify(e, false)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void otherSqlStateIsNoRetry() {
    SQLException e = new SQLException("syntax error", "42601");
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void nullSqlStateIsNoRetry() {
    SQLException e = new SQLException("no state");
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void sqlExceptionAsCauseIsClassified() {
    SQLException cause = new SQLException("conflict", "40001");
    RuntimeException wrapper = new RuntimeException(cause);
    assertThat(SqlStateClassifier.classify(wrapper, false))
        .isEqualTo(RetryStrategy.SAME_CONNECTION);
  }

  @Test
  void isTransactionWideForSerializationFailureAndDeadlock() {
    assertThat(SqlStateClassifier.isTransactionWide(new SQLException("x", "40001"))).isTrue();
    assertThat(SqlStateClassifier.isTransactionWide(new SQLException("x", "40P01"))).isTrue();
  }

  @Test
  void isTransactionWideFalseForUniqueViolationAndOthers() {
    assertThat(SqlStateClassifier.isTransactionWide(new SQLException("x", "23505"))).isFalse();
    assertThat(SqlStateClassifier.isTransactionWide(new RuntimeException("x"))).isFalse();
  }

  @Test
  void isTransactionWideUnwrapsOneCauseLevelLikeClassifyDoes() {
    SQLException cause = new SQLException("conflict", "40001");
    RuntimeException wrapper = new RuntimeException(cause);
    assertThat(SqlStateClassifier.isTransactionWide(wrapper)).isTrue();
  }

  @Test
  void isTransactionRetryableForSerializationDeadlockAndUniqueViolation() {
    assertThat(SqlStateClassifier.isTransactionRetryable(new SQLException("x", "40001"))).isTrue();
    assertThat(SqlStateClassifier.isTransactionRetryable(new SQLException("x", "40P01"))).isTrue();
    assertThat(SqlStateClassifier.isTransactionRetryable(new SQLException("x", "23505"))).isTrue();
  }

  @Test
  void isTransactionRetryableFalseForOthers() {
    assertThat(SqlStateClassifier.isTransactionRetryable(new SQLException("x", "42601"))).isFalse();
    assertThat(SqlStateClassifier.isTransactionRetryable(new RuntimeException("x"))).isFalse();
  }
}
