package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class IntegrationalDriftClassifierTest {

  @Test
  void undefinedTableIsExecutionDrift() {
    assertThat(
            IntegrationalDriftClassifier.isExecutionDrift(
                new SQLException("relation \"albums\" does not exist", "42P01")))
        .isTrue();
  }

  @Test
  void undefinedColumnIsExecutionDrift() {
    assertThat(
            IntegrationalDriftClassifier.isExecutionDrift(
                new SQLException("column \"title\" does not exist", "42703")))
        .isTrue();
  }

  @Test
  void uniqueViolationIsNotExecutionDrift() {
    assertThat(IntegrationalDriftClassifier.isExecutionDrift(new SQLException("dup", "23505")))
        .isFalse();
  }

  @Test
  void connectionExceptionIsNotExecutionDrift() {
    assertThat(
            IntegrationalDriftClassifier.isExecutionDrift(new SQLException("conn lost", "08006")))
        .isFalse();
  }

  @Test
  void nullSqlStateIsNotExecutionDrift() {
    assertThat(IntegrationalDriftClassifier.isExecutionDrift(new SQLException("no state")))
        .isFalse();
  }
}
