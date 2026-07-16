package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ClassifiedSqlFailureTest {

  @Test
  void nonSqlExceptionIsNoRetry() {
    assertThat(new ClassifiedSqlFailure(new RuntimeException("boom")).toRetryStrategy(true))
        .isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void serializationFailureIsSameConnectionRegardlessOfIdempotency() {
    ClassifiedSqlFailure failure = new ClassifiedSqlFailure(new SQLException("conflict", "40001"));
    assertThat(failure.toRetryStrategy(false)).isEqualTo(RetryStrategy.SAME_CONNECTION);
    assertThat(failure.toRetryStrategy(true)).isEqualTo(RetryStrategy.SAME_CONNECTION);
  }

  @Test
  void deadlockDetectedIsSameConnectionRegardlessOfIdempotency() {
    ClassifiedSqlFailure failure = new ClassifiedSqlFailure(new SQLException("deadlock", "40P01"));
    assertThat(failure.toRetryStrategy(false)).isEqualTo(RetryStrategy.SAME_CONNECTION);
    assertThat(failure.toRetryStrategy(true)).isEqualTo(RetryStrategy.SAME_CONNECTION);
  }

  @Test
  void uniqueViolationIsNoRetryForTheStatementLoop() {
    ClassifiedSqlFailure failure = new ClassifiedSqlFailure(new SQLException("dup", "23505"));
    assertThat(failure.toRetryStrategy(false)).isEqualTo(RetryStrategy.NO_RETRY);
    assertThat(failure.toRetryStrategy(true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void connectionExceptionClassIsNewConnectionOnlyWhenIdempotent() {
    ClassifiedSqlFailure failure = new ClassifiedSqlFailure(new SQLException("conn lost", "08006"));
    assertThat(failure.toRetryStrategy(true)).isEqualTo(RetryStrategy.NEW_CONNECTION);
    assertThat(failure.toRetryStrategy(false)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void otherSqlStateIsNoRetry() {
    ClassifiedSqlFailure failure =
        new ClassifiedSqlFailure(new SQLException("syntax error", "42601"));
    assertThat(failure.toRetryStrategy(true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void nullSqlStateIsNoRetry() {
    ClassifiedSqlFailure failure = new ClassifiedSqlFailure(new SQLException("no state"));
    assertThat(failure.toRetryStrategy(true)).isEqualTo(RetryStrategy.NO_RETRY);
  }

  @Test
  void sqlExceptionAsCauseIsClassified() {
    SQLException cause = new SQLException("conflict", "40001");
    RuntimeException wrapper = new RuntimeException(cause);
    assertThat(new ClassifiedSqlFailure(wrapper).toRetryStrategy(false))
        .isEqualTo(RetryStrategy.SAME_CONNECTION);
  }

  @Test
  void isTransactionWideForSerializationFailureAndDeadlock() {
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "40001")).isTransactionWide())
        .isTrue();
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "40P01")).isTransactionWide())
        .isTrue();
  }

  @Test
  void isTransactionWideFalseForUniqueViolationAndOthers() {
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "23505")).isTransactionWide())
        .isFalse();
    assertThat(new ClassifiedSqlFailure(new RuntimeException("x")).isTransactionWide()).isFalse();
  }

  @Test
  void isTransactionWideUnwrapsOneCauseLevelLikeClassifyDoes() {
    SQLException cause = new SQLException("conflict", "40001");
    RuntimeException wrapper = new RuntimeException(cause);
    assertThat(new ClassifiedSqlFailure(wrapper).isTransactionWide()).isTrue();
  }

  @Test
  void isTransactionRetryableForSerializationDeadlockAndUniqueViolation() {
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "40001")).isTransactionRetryable())
        .isTrue();
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "40P01")).isTransactionRetryable())
        .isTrue();
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "23505")).isTransactionRetryable())
        .isTrue();
  }

  @Test
  void isTransactionRetryableFalseForOthers() {
    assertThat(new ClassifiedSqlFailure(new SQLException("x", "42601")).isTransactionRetryable())
        .isFalse();
    assertThat(new ClassifiedSqlFailure(new RuntimeException("x")).isTransactionRetryable())
        .isFalse();
  }
}
