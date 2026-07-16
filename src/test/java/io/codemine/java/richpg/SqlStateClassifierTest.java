package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import io.codemine.java.richpg.SqlStateClassifier.RetryStrategy;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlStateClassifierTest {

  @Test
  void nullFailureIsNoRetry() {
    assertThat(SqlStateClassifier.classify(null, true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

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
  void uniqueViolationIsSameConnectionRegardlessOfIdempotency() {
    SQLException e = new SQLException("dup", "23505");
    assertThat(SqlStateClassifier.classify(e, false)).isEqualTo(RetryStrategy.SAME_CONNECTION);
    assertThat(SqlStateClassifier.classify(e, true)).isEqualTo(RetryStrategy.SAME_CONNECTION);
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
}
