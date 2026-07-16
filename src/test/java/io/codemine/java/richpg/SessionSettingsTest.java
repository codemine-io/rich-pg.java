package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SessionSettingsTest {

  private static SessionSettings defaults() {
    return SessionSettings.defaults("jdbc:postgresql://localhost/test", "user", "pw");
  }

  @Test
  void defaultsPopulatesRequiredFields() {
    var config = defaults();

    assertEquals("jdbc:postgresql://localhost/test", config.jdbcUrl());
    assertEquals("user", config.user());
    assertEquals("pw", config.password());
  }

  @Test
  void defaultsPopulatesOptionalFieldsWithDocumentedDefaults() {
    var config = defaults();

    assertEquals(10, config.maximumPoolSize());
    assertEquals(Duration.ofSeconds(30), config.connectionTimeout());
    assertEquals(Duration.ofSeconds(30), config.statementTimeout());
    assertEquals(3, config.retryAttempts());
    assertEquals(Duration.ofSeconds(1), config.slowQueryLogThreshold());
  }

  @Test
  void defaultsSetHealthCheckTimeoutAndCloseDrainDeadline() {
    SessionSettings s = SessionSettings.defaults("jdbc:postgresql://h/db", "u", "p");
    assertThat(s.healthCheckTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(s.closeDrainDeadline()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void defaultsPopulatesIdentityFieldsWithGenericDefaults() {
    var config = defaults();

    assertEquals("io.codemine.java.rich-pg", config.scopeName());
    assertEquals("1.0.0", config.scopeVersion());
    assertEquals("rich-pg-pool", config.poolName());
    assertEquals("rich-pg", config.artifactName());
  }

  @Test
  void requiredFieldsCannotBeNull() {
    assertThrows(NullPointerException.class, () -> SessionSettings.defaults(null, "user", "pw"));
    assertThrows(
        NullPointerException.class,
        () -> SessionSettings.defaults("jdbc:postgresql://localhost/test", null, "pw"));
    assertThrows(
        NullPointerException.class,
        () -> SessionSettings.defaults("jdbc:postgresql://localhost/test", "user", null));
  }

  @Test
  void withOpenTelemetryRejectsNull() {
    assertThrows(NullPointerException.class, () -> defaults().withOpenTelemetry(null));
  }

  @Test
  void withScopeNameRejectsNull() {
    assertThrows(NullPointerException.class, () -> defaults().withScopeName(null));
  }

  @Test
  void withScopeVersionRejectsNull() {
    assertThrows(NullPointerException.class, () -> defaults().withScopeVersion(null));
  }

  @Test
  void withPoolNameRejectsNull() {
    assertThrows(NullPointerException.class, () -> defaults().withPoolName(null));
  }

  @Test
  void withArtifactNameRejectsNull() {
    assertThrows(NullPointerException.class, () -> defaults().withArtifactName(null));
  }

  @Test
  void maximumPoolSizeMustBeAtLeastOne() {
    assertThrows(IllegalArgumentException.class, () -> defaults().withMaximumPoolSize(0));
    assertThrows(IllegalArgumentException.class, () -> defaults().withMaximumPoolSize(-1));
    assertDoesNotThrow(() -> defaults().withMaximumPoolSize(1));
  }

  @Test
  void retryAttemptsMustBeAtLeastOne() {
    assertThrows(IllegalArgumentException.class, () -> defaults().withRetryAttempts(0));
    assertThrows(IllegalArgumentException.class, () -> defaults().withRetryAttempts(-1));
    assertDoesNotThrow(() -> defaults().withRetryAttempts(1));
  }

  @Test
  void statementTimeoutAllowsZeroButRejectsNegative() {
    assertDoesNotThrow(() -> defaults().withStatementTimeout(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> defaults().withStatementTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void slowQueryLogThresholdAllowsZeroButRejectsNegative() {
    assertDoesNotThrow(() -> defaults().withSlowQueryLogThreshold(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> defaults().withSlowQueryLogThreshold(Duration.ofSeconds(-1)));
  }

  @Test
  void connectionTimeoutAllowsZeroButRejectsNegative() {
    assertDoesNotThrow(() -> defaults().withConnectionTimeout(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> defaults().withConnectionTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void healthCheckTimeoutMustNotBeNegative() {
    assertThatThrownBy(() -> defaults().withHealthCheckTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void closeDrainDeadlineMustNotBeNegative() {
    assertThatThrownBy(() -> defaults().withCloseDrainDeadline(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withMaximumPoolSizePreservesEveryOtherField() {
    assertWitherChangesOnlyOneField(
        s -> s.withMaximumPoolSize(42), "maximumPoolSize", SessionSettings::maximumPoolSize, 42);
  }

  @Test
  void withScopeNamePreservesEveryOtherField() {
    assertWitherChangesOnlyOneField(
        s -> s.withScopeName("io.pgenie.artifacts.myspace.musiccatalogue"),
        "scopeName",
        SessionSettings::scopeName,
        "io.pgenie.artifacts.myspace.musiccatalogue");
  }

  @Test
  void withScopeVersionPreservesEveryOtherField() {
    assertWitherChangesOnlyOneField(
        s -> s.withScopeVersion("1.0.1"), "scopeVersion", SessionSettings::scopeVersion, "1.0.1");
  }

  @Test
  void withPoolNamePreservesEveryOtherField() {
    assertWitherChangesOnlyOneField(
        s -> s.withPoolName("music-catalogue-pool"),
        "poolName",
        SessionSettings::poolName,
        "music-catalogue-pool");
  }

  @Test
  void withArtifactNamePreservesEveryOtherField() {
    assertWitherChangesOnlyOneField(
        s -> s.withArtifactName("music-catalogue"),
        "artifactName",
        SessionSettings::artifactName,
        "music-catalogue");
  }

  /**
   * Applies {@code wither} to {@link #defaults()} and asserts that only the record component named
   * {@code changedComponent} differs from the original, and that it now equals {@code
   * expectedNewValue}. Every other record component (checked via reflection, so newly added fields
   * are covered automatically) must be unchanged.
   */
  private static void assertWitherChangesOnlyOneField(
      Function<SessionSettings, SessionSettings> wither,
      String changedComponent,
      Function<SessionSettings, Object> getter,
      Object expectedNewValue) {
    SessionSettings original = defaults();
    SessionSettings tweaked = wither.apply(original);

    assertEquals(expectedNewValue, getter.apply(tweaked));

    for (RecordComponent component : SessionSettings.class.getRecordComponents()) {
      if (component.getName().equals(changedComponent)) {
        continue;
      }
      try {
        Object originalValue = component.getAccessor().invoke(original);
        Object tweakedValue = component.getAccessor().invoke(tweaked);
        assertEquals(originalValue, tweakedValue, "field " + component.getName() + " changed");
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Test
  void toStringRedactsPassword() {
    var text = defaults().toString();

    assertFalse(text.contains("pw"), "toString leaked the raw password: " + text);
    assertTrue(text.contains("***"), "toString should contain a redaction marker: " + text);
  }
}
