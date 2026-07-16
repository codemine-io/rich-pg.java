package io.codemine.java.richpg.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TransactionSettings}. */
public class TransactionSettingsTest {

  @Test
  void defaultUsesSerializableIsolationAndIsNotReadOnlyAndRetries() {
    assertEquals(
        IsolationLevel.SERIALIZABLE, TransactionSettings.SERIALIZABLE_WRITE.isolationLevel());
    assertFalse(TransactionSettings.SERIALIZABLE_WRITE.readOnly());
    assertEquals(7, TransactionSettings.SERIALIZABLE_WRITE.maxAttempts());
  }

  @Test
  void withIsolationLevelReturnsModifiedCopyWithoutMutatingOriginal() {
    TransactionSettings modified =
        TransactionSettings.SERIALIZABLE_WRITE.withIsolationLevel(IsolationLevel.READ_COMMITTED);

    assertEquals(IsolationLevel.READ_COMMITTED, modified.isolationLevel());
    assertEquals(
        IsolationLevel.SERIALIZABLE, TransactionSettings.SERIALIZABLE_WRITE.isolationLevel());
  }

  @Test
  void withReadOnlyReturnsModifiedCopyWithoutMutatingOriginal() {
    TransactionSettings modified = TransactionSettings.SERIALIZABLE_WRITE.withReadOnly(true);

    assertTrue(modified.readOnly());
    assertFalse(TransactionSettings.SERIALIZABLE_WRITE.readOnly());
  }

  @Test
  void withMaxAttemptsReturnsModifiedCopyWithoutMutatingOriginal() {
    TransactionSettings modified = TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(5);

    assertEquals(5, modified.maxAttempts());
    assertEquals(7, TransactionSettings.SERIALIZABLE_WRITE.maxAttempts());
  }

  @Test
  void constructorRejectsNullIsolationLevel() {
    var thrown =
        assertThrows(NullPointerException.class, () -> new TransactionSettings(null, false, 1));
    assertEquals("isolationLevel", thrown.getMessage());
  }

  @Test
  void withIsolationLevelRejectsNull() {
    var thrown =
        assertThrows(
            NullPointerException.class,
            () -> TransactionSettings.SERIALIZABLE_WRITE.withIsolationLevel(null));
    assertEquals("level", thrown.getMessage());
  }

  @Test
  void withMaxAttemptsRejectsLessThanOne() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(0));
    assertEquals("maxAttempts must be at least 1", thrown.getMessage());
  }
}
