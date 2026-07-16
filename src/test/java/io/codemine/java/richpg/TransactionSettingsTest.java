package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TransactionSettings}. */
public class TransactionSettingsTest {

  @Test
  void defaultUsesSerializableIsolationAndIsNotReadOnly() {
    assertEquals(
        IsolationLevel.SERIALIZABLE, TransactionSettings.SERIALIZABLE_WRITE.isolationLevel());
    assertFalse(TransactionSettings.SERIALIZABLE_WRITE.readOnly());
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
  void constructorRejectsNullIsolationLevel() {
    var thrown =
        assertThrows(NullPointerException.class, () -> new TransactionSettings(null, false));
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
}
