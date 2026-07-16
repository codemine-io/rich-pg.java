package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SessionSettingsTest {

  private static SessionSettings defaults() {
    return SessionSettings.defaults("jdbc:postgresql://localhost/test", "user", "pw");
  }

  private static SessionSettings base() {
    return defaults();
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
    assertEquals(7, config.retryAttempts());
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
    assertThatThrownBy(() -> base().withHealthCheckTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void closeDrainDeadlineMustNotBeNegative() {
    assertThatThrownBy(() -> base().withCloseDrainDeadline(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withersPreserveEveryOtherField() {
    var tweaked = defaults().withMaximumPoolSize(42);
    var expected =
        new SessionSettings(
            "jdbc:postgresql://localhost/test",
            "user",
            "pw",
            42,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            7,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            tweaked.openTelemetry(),
            "io.codemine.java.rich-pg",
            "1.0.0",
            "rich-pg-pool",
            "rich-pg");

    assertEquals(expected, tweaked);
  }

  @Test
  void withScopeNamePreservesEveryOtherField() {
    var tweaked = defaults().withScopeName("io.pgenie.artifacts.myspace.musiccatalogue");
    var expected =
        new SessionSettings(
            "jdbc:postgresql://localhost/test",
            "user",
            "pw",
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            7,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            tweaked.openTelemetry(),
            "io.pgenie.artifacts.myspace.musiccatalogue",
            "1.0.0",
            "rich-pg-pool",
            "rich-pg");

    assertEquals(expected, tweaked);
  }

  @Test
  void withScopeVersionPreservesEveryOtherField() {
    var tweaked = defaults().withScopeVersion("1.0.1");
    var expected =
        new SessionSettings(
            "jdbc:postgresql://localhost/test",
            "user",
            "pw",
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            7,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            tweaked.openTelemetry(),
            "io.codemine.java.rich-pg",
            "1.0.1",
            "rich-pg-pool",
            "rich-pg");

    assertEquals(expected, tweaked);
  }

  @Test
  void withPoolNamePreservesEveryOtherField() {
    var tweaked = defaults().withPoolName("music-catalogue-pool");
    var expected =
        new SessionSettings(
            "jdbc:postgresql://localhost/test",
            "user",
            "pw",
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            7,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            tweaked.openTelemetry(),
            "io.codemine.java.rich-pg",
            "1.0.0",
            "music-catalogue-pool",
            "rich-pg");

    assertEquals(expected, tweaked);
  }

  @Test
  void withArtifactNamePreservesEveryOtherField() {
    var tweaked = defaults().withArtifactName("music-catalogue");
    var expected =
        new SessionSettings(
            "jdbc:postgresql://localhost/test",
            "user",
            "pw",
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            7,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            tweaked.openTelemetry(),
            "io.codemine.java.rich-pg",
            "1.0.0",
            "rich-pg-pool",
            "music-catalogue");

    assertEquals(expected, tweaked);
  }

  @Test
  void toStringRedactsPassword() {
    var text = defaults().toString();

    assertFalse(text.contains("pw"), "toString leaked the raw password: " + text);
    assertTrue(text.contains("***"), "toString should contain a redaction marker: " + text);
  }
}
