package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatementHealthTrackerTest {

  private static final class SomeStatement {}

  private static final class OtherStatement {}

  @Test
  void startsHealthyWithNoBrokenClasses() {
    StatementHealthTracker tracker = new StatementHealthTracker();

    assertThat(tracker.isHealthy()).isTrue();
    assertThat(tracker.brokenStatementClasses()).isEmpty();
  }

  @Test
  void markBrokenMakesItUnhealthyAndReportsTheClass() {
    StatementHealthTracker tracker = new StatementHealthTracker();

    tracker.markBroken(SomeStatement.class);

    assertThat(tracker.isHealthy()).isFalse();
    assertThat(tracker.brokenStatementClasses()).containsExactly(SomeStatement.class);
  }

  @Test
  void markHealthyRemovesTheClassAndRestoresOverallHealth() {
    StatementHealthTracker tracker = new StatementHealthTracker();
    tracker.markBroken(SomeStatement.class);

    tracker.markHealthy(SomeStatement.class);

    assertThat(tracker.isHealthy()).isTrue();
    assertThat(tracker.brokenStatementClasses()).isEmpty();
  }

  @Test
  void markHealthyOnAnUnbrokenClassIsANoop() {
    StatementHealthTracker tracker = new StatementHealthTracker();

    tracker.markHealthy(SomeStatement.class);

    assertThat(tracker.isHealthy()).isTrue();
  }

  @Test
  void otherBrokenClassesDoNotAffectAnUnrelatedClass() {
    StatementHealthTracker tracker = new StatementHealthTracker();

    tracker.markBroken(SomeStatement.class);
    tracker.markBroken(OtherStatement.class);
    tracker.markHealthy(SomeStatement.class);

    assertThat(tracker.isHealthy()).isFalse();
    assertThat(tracker.brokenStatementClasses()).containsExactly(OtherStatement.class);
  }
}
