package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link StatementSettings}. */
class StatementSettingsTest {

  @Test
  void defaultUsesSevenMaxAttempts() {
    assertEquals(7, StatementSettings.DEFAULT.maxAttempts());
  }

  @Test
  void withMaxAttemptsReturnsModifiedCopyWithoutMutatingOriginal() {
    StatementSettings modified = StatementSettings.DEFAULT.withMaxAttempts(3);

    assertEquals(3, modified.maxAttempts());
    assertEquals(7, StatementSettings.DEFAULT.maxAttempts());
  }

  @Test
  void constructorRejectsMaxAttemptsLessThanOne() {
    var thrown = assertThrows(IllegalArgumentException.class, () -> new StatementSettings(0));
    assertEquals("maxAttempts must be at least 1", thrown.getMessage());
  }
}
