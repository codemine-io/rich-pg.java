package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TransactionMode}. */
public class TransactionModeTest {

  @Test
  void defaultUsesSerializableIsolationAndIsNotReadOnly() {
    assertEquals(IsolationLevel.SERIALIZABLE, TransactionMode.SERIALIZABLE_WRITE.isolationLevel());
    assertFalse(TransactionMode.SERIALIZABLE_WRITE.readOnly());
  }

  @Test
  void withIsolationLevelReturnsModifiedCopyWithoutMutatingOriginal() {
    TransactionMode modified =
        TransactionMode.SERIALIZABLE_WRITE.withIsolationLevel(IsolationLevel.READ_COMMITTED);

    assertEquals(IsolationLevel.READ_COMMITTED, modified.isolationLevel());
    assertEquals(IsolationLevel.SERIALIZABLE, TransactionMode.SERIALIZABLE_WRITE.isolationLevel());
  }

  @Test
  void withReadOnlyReturnsModifiedCopyWithoutMutatingOriginal() {
    TransactionMode modified = TransactionMode.SERIALIZABLE_WRITE.withReadOnly(true);

    assertTrue(modified.readOnly());
    assertFalse(TransactionMode.SERIALIZABLE_WRITE.readOnly());
  }

  @Test
  void constructorRejectsNullIsolationLevel() {
    var thrown = assertThrows(NullPointerException.class, () -> new TransactionMode(null, false));
    assertEquals("isolationLevel", thrown.getMessage());
  }

  @Test
  void withIsolationLevelRejectsNull() {
    var thrown =
        assertThrows(
            NullPointerException.class,
            () -> TransactionMode.SERIALIZABLE_WRITE.withIsolationLevel(null));
    assertEquals("level", thrown.getMessage());
  }
}
