package io.codemine.java.richpg;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a rich-pg session.
 *
 * <p>The JDBC URL identifies the database host, port and name; credentials are supplied separately
 * so that they can be rotated, stored in secret managers, or overridden without touching the
 * connection string.
 *
 * <p>This single record owns both connection/pooling settings and observability settings
 * (instrumentation scope, pool naming, artifact identity) so that generated artifacts have exactly
 * one config type to construct and pass around.
 *
 * @param jdbcUrl the JDBC URL of the PostgreSQL database
 * @param user the database user
 * @param password the database password
 * @param maximumPoolSize maximum number of connections maintained in the HikariCP pool; at least 1
 * @param connectionTimeout maximum time to wait for a connection from the pool before failing; must
 *     not be negative
 * @param statementTimeout maximum time a single statement is allowed to execute before being
 *     cancelled; zero means no timeout; must not be negative
 * @param retryAttempts number of times a statement or transaction is retried when a serialization
 *     failure, deadlock, or transient connection failure is detected; at least 1; shared by both
 *     the statement and transaction retry loops
 * @param slowQueryLogThreshold queries running longer than this threshold are logged as slow
 *     queries; zero logs every query; must not be negative
 * @param healthCheckTimeout maximum time the background health probe waits for the database
 *     round-trip before failing; must not be negative
 * @param healthCheckPeriod interval between background health probes; {@link Session#healthCheck()}
 *     returns the cached result of the most recent probe; must be positive
 * @param closeDrainDeadline maximum time {@link Session#close()} waits for active connections to
 *     drain before forcing pool shutdown; must not be negative
 * @param durationHistogramBoundaries explicit bucket boundaries, in seconds, advised for the {@code
 *     db.client.operation.duration} histogram; must not be null or empty
 * @param openTelemetry OpenTelemetry instance used for tracing and metrics
 * @param scopeName the OpenTelemetry instrumentation-scope name, e.g. what {@code
 *     openTelemetry.getTracer(scopeName, scopeVersion)} uses
 * @param scopeVersion the OpenTelemetry instrumentation-scope version
 * @param poolName the HikariCP pool name; also used to disambiguate pool metrics across multiple
 *     instances
 * @param artifactName the name of the generated artifact this config belongs to, e.g. {@code
 *     "music-catalogue"}
 */
public record SessionSettings(
    String jdbcUrl,
    String user,
    String password,
    int maximumPoolSize,
    Duration connectionTimeout,
    Duration statementTimeout,
    int retryAttempts,
    Duration slowQueryLogThreshold,
    Duration healthCheckTimeout,
    Duration healthCheckPeriod,
    Duration closeDrainDeadline,
    List<Double> durationHistogramBoundaries,
    OpenTelemetry openTelemetry,
    String scopeName,
    String scopeVersion,
    String poolName,
    String artifactName) {

  /** The version of this module, used as the {@link #defaults} instrumentation-scope version. */
  private static final String MODULE_VERSION = "1.0.0";

  /** Default number of retry attempts shared by the statement and transaction retry loops. */
  static final int DEFAULT_RETRY_ATTEMPTS = 3;

  /** Default explicit bucket boundaries, in seconds, for the duration histogram. */
  static final List<Double> DEFAULT_DURATION_HISTOGRAM_BOUNDARIES =
      List.of(0.001, 0.01, 0.1, 1.0, 10.0, 100.0);

  /**
   * Validates the record's components.
   *
   * @throws NullPointerException if any reference-typed component is null
   * @throws IllegalArgumentException if a numeric or duration component violates its stated bound
   */
  public SessionSettings {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(connectionTimeout, "connectionTimeout");
    Objects.requireNonNull(statementTimeout, "statementTimeout");
    Objects.requireNonNull(slowQueryLogThreshold, "slowQueryLogThreshold");
    Objects.requireNonNull(healthCheckTimeout, "healthCheckTimeout");
    Objects.requireNonNull(healthCheckPeriod, "healthCheckPeriod");
    Objects.requireNonNull(closeDrainDeadline, "closeDrainDeadline");
    Objects.requireNonNull(durationHistogramBoundaries, "durationHistogramBoundaries");
    Objects.requireNonNull(openTelemetry, "openTelemetry");
    Objects.requireNonNull(scopeName, "scopeName");
    Objects.requireNonNull(scopeVersion, "scopeVersion");
    Objects.requireNonNull(poolName, "poolName");
    Objects.requireNonNull(artifactName, "artifactName");
    if (maximumPoolSize < 1) {
      throw new IllegalArgumentException("maximumPoolSize must be at least 1");
    }
    if (connectionTimeout.isNegative()) {
      throw new IllegalArgumentException("connectionTimeout must not be negative");
    }
    if (statementTimeout.isNegative()) {
      throw new IllegalArgumentException("statementTimeout must not be negative");
    }
    if (retryAttempts < 1) {
      throw new IllegalArgumentException("retryAttempts must be at least 1");
    }
    if (slowQueryLogThreshold.isNegative()) {
      throw new IllegalArgumentException("slowQueryLogThreshold must not be negative");
    }
    if (healthCheckTimeout.isNegative()) {
      throw new IllegalArgumentException("healthCheckTimeout must not be negative");
    }
    if (healthCheckPeriod.isNegative() || healthCheckPeriod.isZero()) {
      throw new IllegalArgumentException("healthCheckPeriod must be positive");
    }
    if (closeDrainDeadline.isNegative()) {
      throw new IllegalArgumentException("closeDrainDeadline must not be negative");
    }
    if (durationHistogramBoundaries.isEmpty()) {
      throw new IllegalArgumentException("durationHistogramBoundaries must not be empty");
    }
  }

  /**
   * Creates settings with the given required fields and default values for everything else: a
   * maximum pool size of 10, a 30-second connection timeout, a 30-second statement timeout, 3 retry
   * attempts, a 1-second slow-query-log threshold, a 2-second health-check timeout, a 10-second
   * health-check period, a 10-second close-drain deadline, duration histogram boundaries of {@code
   * [0.001, 0.01, 0.1, 1.0, 10.0, 100.0]} seconds, and the global {@link OpenTelemetry} instance.
   *
   * <p>Because this factory has no knowledge of the calling artifact, the instrumentation scope
   * name, scope version, pool name and artifact name are populated with generic, library-level
   * defaults ({@code "io.codemine.java.rich-pg"}, this module's own version, {@code "rich-pg-pool"}
   * and {@code "rich-pg"} respectively) so that the type remains usable standalone, without a
   * generator. Real generated callers are expected to override all four via {@link #withScopeName},
   * {@link #withScopeVersion}, {@link #withPoolName} and {@link #withArtifactName} with values that
   * identify their own artifact.
   *
   * @param jdbcUrl the JDBC URL of the PostgreSQL database
   * @param user the database user
   * @param password the database password
   * @return a fully-populated settings instance
   * @throws NullPointerException if any argument is null
   */
  public static SessionSettings defaults(String jdbcUrl, String user, String password) {
    return new SessionSettings(
        jdbcUrl,
        user,
        password,
        10,
        Duration.ofSeconds(30),
        Duration.ofSeconds(30),
        DEFAULT_RETRY_ATTEMPTS,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(10),
        Duration.ofSeconds(10),
        DEFAULT_DURATION_HISTOGRAM_BOUNDARIES,
        GlobalOpenTelemetry.get(),
        "io.codemine.java.rich-pg",
        MODULE_VERSION,
        "rich-pg-pool",
        "rich-pg");
  }

  /**
   * Returns a copy of this settings instance with the given JDBC URL.
   *
   * @param jdbcUrl the JDBC URL to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code jdbcUrl} is null
   */
  public SessionSettings withJdbcUrl(String jdbcUrl) {
    Fields f = fields();
    f.jdbcUrl = jdbcUrl;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given database user.
   *
   * @param user the user to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code user} is null
   */
  public SessionSettings withUser(String user) {
    Fields f = fields();
    f.user = user;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given database password.
   *
   * @param password the password to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code password} is null
   */
  public SessionSettings withPassword(String password) {
    Fields f = fields();
    f.password = password;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given maximum pool size.
   *
   * @param maximumPoolSize the maximum pool size to apply; at least 1
   * @return a new {@code SessionSettings}
   * @throws IllegalArgumentException if {@code maximumPoolSize} is less than 1
   */
  public SessionSettings withMaximumPoolSize(int maximumPoolSize) {
    Fields f = fields();
    f.maximumPoolSize = maximumPoolSize;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given connection timeout.
   *
   * @param connectionTimeout the connection timeout to apply; must not be negative
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code connectionTimeout} is null
   * @throws IllegalArgumentException if {@code connectionTimeout} is negative
   */
  public SessionSettings withConnectionTimeout(Duration connectionTimeout) {
    Fields f = fields();
    f.connectionTimeout = connectionTimeout;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given statement timeout.
   *
   * @param statementTimeout the statement timeout to apply; zero means no timeout; must not be
   *     negative
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code statementTimeout} is null
   * @throws IllegalArgumentException if {@code statementTimeout} is negative
   */
  public SessionSettings withStatementTimeout(Duration statementTimeout) {
    Fields f = fields();
    f.statementTimeout = statementTimeout;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given retry attempts.
   *
   * @param retryAttempts the number of retry attempts to apply; at least 1
   * @return a new {@code SessionSettings}
   * @throws IllegalArgumentException if {@code retryAttempts} is less than 1
   */
  public SessionSettings withRetryAttempts(int retryAttempts) {
    Fields f = fields();
    f.retryAttempts = retryAttempts;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given slow-query-log threshold.
   *
   * @param slowQueryLogThreshold the threshold to apply; zero logs every query; must not be
   *     negative
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code slowQueryLogThreshold} is null
   * @throws IllegalArgumentException if {@code slowQueryLogThreshold} is negative
   */
  public SessionSettings withSlowQueryLogThreshold(Duration slowQueryLogThreshold) {
    Fields f = fields();
    f.slowQueryLogThreshold = slowQueryLogThreshold;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given health-check timeout.
   *
   * @param healthCheckTimeout the timeout to apply; must not be negative
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code healthCheckTimeout} is null
   * @throws IllegalArgumentException if {@code healthCheckTimeout} is negative
   */
  public SessionSettings withHealthCheckTimeout(Duration healthCheckTimeout) {
    Fields f = fields();
    f.healthCheckTimeout = healthCheckTimeout;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given health-check period.
   *
   * @param healthCheckPeriod the period to apply; must be positive
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code healthCheckPeriod} is null
   * @throws IllegalArgumentException if {@code healthCheckPeriod} is not positive
   */
  public SessionSettings withHealthCheckPeriod(Duration healthCheckPeriod) {
    Fields f = fields();
    f.healthCheckPeriod = healthCheckPeriod;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given close-drain deadline.
   *
   * @param closeDrainDeadline the deadline to apply; must not be negative
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code closeDrainDeadline} is null
   * @throws IllegalArgumentException if {@code closeDrainDeadline} is negative
   */
  public SessionSettings withCloseDrainDeadline(Duration closeDrainDeadline) {
    Fields f = fields();
    f.closeDrainDeadline = closeDrainDeadline;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given duration histogram boundaries.
   *
   * @param durationHistogramBoundaries the explicit bucket boundaries, in seconds, to advise for
   *     the {@code db.client.operation.duration} histogram; must not be empty
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code durationHistogramBoundaries} is null
   * @throws IllegalArgumentException if {@code durationHistogramBoundaries} is empty
   */
  public SessionSettings withDurationHistogramBoundaries(List<Double> durationHistogramBoundaries) {
    Fields f = fields();
    f.durationHistogramBoundaries = durationHistogramBoundaries;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given OpenTelemetry instance.
   *
   * @param openTelemetry the OpenTelemetry instance to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code openTelemetry} is null
   */
  public SessionSettings withOpenTelemetry(OpenTelemetry openTelemetry) {
    Fields f = fields();
    f.openTelemetry = openTelemetry;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given instrumentation-scope name.
   *
   * @param scopeName the scope name to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code scopeName} is null
   */
  public SessionSettings withScopeName(String scopeName) {
    Fields f = fields();
    f.scopeName = scopeName;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given instrumentation-scope version.
   *
   * @param scopeVersion the scope version to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code scopeVersion} is null
   */
  public SessionSettings withScopeVersion(String scopeVersion) {
    Fields f = fields();
    f.scopeVersion = scopeVersion;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given pool name.
   *
   * @param poolName the pool name to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code poolName} is null
   */
  public SessionSettings withPoolName(String poolName) {
    Fields f = fields();
    f.poolName = poolName;
    return f.build();
  }

  /**
   * Returns a copy of this settings instance with the given artifact name.
   *
   * @param artifactName the artifact name to apply
   * @return a new {@code SessionSettings}
   * @throws NullPointerException if {@code artifactName} is null
   */
  public SessionSettings withArtifactName(String artifactName) {
    Fields f = fields();
    f.artifactName = artifactName;
    return f.build();
  }

  private Fields fields() {
    Fields f = new Fields();
    f.jdbcUrl = jdbcUrl;
    f.user = user;
    f.password = password;
    f.maximumPoolSize = maximumPoolSize;
    f.connectionTimeout = connectionTimeout;
    f.statementTimeout = statementTimeout;
    f.retryAttempts = retryAttempts;
    f.slowQueryLogThreshold = slowQueryLogThreshold;
    f.healthCheckTimeout = healthCheckTimeout;
    f.healthCheckPeriod = healthCheckPeriod;
    f.closeDrainDeadline = closeDrainDeadline;
    f.durationHistogramBoundaries = durationHistogramBoundaries;
    f.openTelemetry = openTelemetry;
    f.scopeName = scopeName;
    f.scopeVersion = scopeVersion;
    f.poolName = poolName;
    f.artifactName = artifactName;
    return f;
  }

  /**
   * Every component, mutable, so a {@code with*} method can change exactly the field it names and
   * pass the rest through unchanged without re-listing all 16 constructor arguments at each call
   * site. Not part of the public API; the public shape stays a record with individual withers.
   */
  private static final class Fields {
    String jdbcUrl;
    String user;
    String password;
    int maximumPoolSize;
    Duration connectionTimeout;
    Duration statementTimeout;
    int retryAttempts;
    Duration slowQueryLogThreshold;
    Duration healthCheckTimeout;
    Duration healthCheckPeriod;
    Duration closeDrainDeadline;
    List<Double> durationHistogramBoundaries;
    OpenTelemetry openTelemetry;
    String scopeName;
    String scopeVersion;
    String poolName;
    String artifactName;

    SessionSettings build() {
      return new SessionSettings(
          jdbcUrl,
          user,
          password,
          maximumPoolSize,
          connectionTimeout,
          statementTimeout,
          retryAttempts,
          slowQueryLogThreshold,
          healthCheckTimeout,
          healthCheckPeriod,
          closeDrainDeadline,
          durationHistogramBoundaries,
          openTelemetry,
          scopeName,
          scopeVersion,
          poolName,
          artifactName);
    }
  }

  /** Redacts {@link #password()} so it can't leak into logs or exception messages. */
  @Override
  public String toString() {
    return "SessionSettings["
        + "jdbcUrl="
        + jdbcUrl
        + ", user="
        + user
        + ", password=***"
        + ", maximumPoolSize="
        + maximumPoolSize
        + ", connectionTimeout="
        + connectionTimeout
        + ", statementTimeout="
        + statementTimeout
        + ", retryAttempts="
        + retryAttempts
        + ", slowQueryLogThreshold="
        + slowQueryLogThreshold
        + ", healthCheckTimeout="
        + healthCheckTimeout
        + ", healthCheckPeriod="
        + healthCheckPeriod
        + ", closeDrainDeadline="
        + closeDrainDeadline
        + ", durationHistogramBoundaries="
        + durationHistogramBoundaries
        + ", openTelemetry="
        + openTelemetry
        + ", scopeName="
        + scopeName
        + ", scopeVersion="
        + scopeVersion
        + ", poolName="
        + poolName
        + ", artifactName="
        + artifactName
        + ']';
  }

  /**
   * Builds a {@link HikariDataSource} configured from this settings instance's connection and pool
   * settings.
   *
   * @return a new HikariCP-backed data source
   */
  HikariDataSource toHikariDataSource() {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(jdbcUrl);
    hc.setUsername(user);
    hc.setPassword(password);
    hc.setMaximumPoolSize(maximumPoolSize);
    hc.setConnectionTimeout(connectionTimeout.toMillis());
    hc.setPoolName(poolName);
    if (statementTimeout.toMillis() > 0) {
      hc.setConnectionInitSql("SET statement_timeout = '" + statementTimeout.toMillis() + "ms'");
    }
    return new HikariDataSource(hc);
  }
}
