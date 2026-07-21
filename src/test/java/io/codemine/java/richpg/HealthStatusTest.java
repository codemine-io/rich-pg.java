package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HealthStatusTest {

  private static final class SomeStatement {}

  @Test
  void healthyWhenProbeSucceededAndNoBrokenClasses() {
    HealthStatus status = new HealthStatus(true, Set.of());

    assertThat(status.healthy()).isTrue();
  }

  @Test
  void unhealthyWhenProbeFailedEvenWithNoBrokenClasses() {
    HealthStatus status = new HealthStatus(false, Set.of());

    assertThat(status.healthy()).isFalse();
  }

  @Test
  void unhealthyWhenProbeSucceededButAStatementClassIsBroken() {
    HealthStatus status = new HealthStatus(true, Set.of(SomeStatement.class));

    assertThat(status.healthy()).isFalse();
    assertThat(status.brokenStatementClasses()).containsExactly(SomeStatement.class);
  }

  @Test
  void brokenStatementClassesIsDefensivelyCopied() {
    var mutable = new java.util.HashSet<Class<?>>();
    mutable.add(SomeStatement.class);
    HealthStatus status = new HealthStatus(true, mutable);

    mutable.clear();

    assertThat(status.brokenStatementClasses()).containsExactly(SomeStatement.class);
  }
}
