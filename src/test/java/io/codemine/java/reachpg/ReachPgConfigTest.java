package io.codemine.java.reachpg;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReachPgConfigTest {

    private static ReachPgConfig defaults() {
        return ReachPgConfig.defaults("jdbc:postgresql://localhost/test", "user", "pw");
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
        assertEquals(3, config.transactionRetryAttempts());
        assertEquals(Duration.ofSeconds(1), config.slowQueryLogThreshold());
    }

    @Test
    void defaultsPopulatesIdentityFieldsWithGenericDefaults() {
        var config = defaults();

        assertEquals("io.codemine.java.reach-pg", config.scopeName());
        assertEquals("1.0.0", config.scopeVersion());
        assertEquals("reach-pg-pool", config.poolName());
        assertEquals("reach-pg", config.artifactName());
    }

    @Test
    void requiredFieldsCannotBeNull() {
        assertThrows(NullPointerException.class,
                () -> ReachPgConfig.defaults(null, "user", "pw"));
        assertThrows(NullPointerException.class,
                () -> ReachPgConfig.defaults("jdbc:postgresql://localhost/test", null, "pw"));
        assertThrows(NullPointerException.class,
                () -> ReachPgConfig.defaults("jdbc:postgresql://localhost/test", "user", null));
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
    void transactionRetryAttemptsMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> defaults().withTransactionRetryAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> defaults().withTransactionRetryAttempts(-1));
        assertDoesNotThrow(() -> defaults().withTransactionRetryAttempts(1));
    }

    @Test
    void statementTimeoutAllowsZeroButRejectsNegative() {
        assertDoesNotThrow(() -> defaults().withStatementTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> defaults().withStatementTimeout(Duration.ofSeconds(-1)));
    }

    @Test
    void slowQueryLogThresholdAllowsZeroButRejectsNegative() {
        assertDoesNotThrow(() -> defaults().withSlowQueryLogThreshold(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> defaults().withSlowQueryLogThreshold(Duration.ofSeconds(-1)));
    }

    @Test
    void connectionTimeoutAllowsZeroButRejectsNegative() {
        assertDoesNotThrow(() -> defaults().withConnectionTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> defaults().withConnectionTimeout(Duration.ofSeconds(-1)));
    }

    @Test
    void withersPreserveEveryOtherField() {
        var tweaked = defaults().withMaximumPoolSize(42);
        var expected = new ReachPgConfig(
                "jdbc:postgresql://localhost/test", "user", "pw",
                42, Duration.ofSeconds(30), Duration.ofSeconds(30),
                3, Duration.ofSeconds(1), tweaked.openTelemetry(),
                "io.codemine.java.reach-pg", "1.0.0", "reach-pg-pool", "reach-pg");

        assertEquals(expected, tweaked);
    }

    @Test
    void withScopeNamePreservesEveryOtherField() {
        var tweaked = defaults().withScopeName("io.pgenie.artifacts.myspace.musiccatalogue");
        var expected = new ReachPgConfig(
                "jdbc:postgresql://localhost/test", "user", "pw",
                10, Duration.ofSeconds(30), Duration.ofSeconds(30),
                3, Duration.ofSeconds(1), tweaked.openTelemetry(),
                "io.pgenie.artifacts.myspace.musiccatalogue", "1.0.0", "reach-pg-pool", "reach-pg");

        assertEquals(expected, tweaked);
    }

    @Test
    void withScopeVersionPreservesEveryOtherField() {
        var tweaked = defaults().withScopeVersion("1.0.1");
        var expected = new ReachPgConfig(
                "jdbc:postgresql://localhost/test", "user", "pw",
                10, Duration.ofSeconds(30), Duration.ofSeconds(30),
                3, Duration.ofSeconds(1), tweaked.openTelemetry(),
                "io.codemine.java.reach-pg", "1.0.1", "reach-pg-pool", "reach-pg");

        assertEquals(expected, tweaked);
    }

    @Test
    void withPoolNamePreservesEveryOtherField() {
        var tweaked = defaults().withPoolName("music-catalogue-pool");
        var expected = new ReachPgConfig(
                "jdbc:postgresql://localhost/test", "user", "pw",
                10, Duration.ofSeconds(30), Duration.ofSeconds(30),
                3, Duration.ofSeconds(1), tweaked.openTelemetry(),
                "io.codemine.java.reach-pg", "1.0.0", "music-catalogue-pool", "reach-pg");

        assertEquals(expected, tweaked);
    }

    @Test
    void withArtifactNamePreservesEveryOtherField() {
        var tweaked = defaults().withArtifactName("music-catalogue");
        var expected = new ReachPgConfig(
                "jdbc:postgresql://localhost/test", "user", "pw",
                10, Duration.ofSeconds(30), Duration.ofSeconds(30),
                3, Duration.ofSeconds(1), tweaked.openTelemetry(),
                "io.codemine.java.reach-pg", "1.0.0", "reach-pg-pool", "music-catalogue");

        assertEquals(expected, tweaked);
    }

    @Test
    void toStringRedactsPassword() {
        var text = defaults().toString();

        assertFalse(text.contains("pw"), "toString leaked the raw password: " + text);
        assertTrue(text.contains("***"), "toString should contain a redaction marker: " + text);
    }
}
