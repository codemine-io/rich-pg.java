# rich-pg

[![docs](https://img.shields.io/badge/docs-all-white)](https://codemine.io/rich-pg.java/)
[![maven](https://img.shields.io/badge/maven-latest-green)](https://codemine.io/rich-pg.java/latest/)
[![javadoc](https://img.shields.io/badge/javadoc-latest-green)](https://codemine.io/rich-pg.java/latest/apidocs/)
[![javadoc](https://javadoc.io/badge2/io.codemine.java/rich-pg/javadoc.svg)](https://javadoc.io/doc/io.codemine.java/rich-pg)
[![Maven Central Version](https://img.shields.io/maven-central/v/io.codemine.java/rich-pg)](https://central.sonatype.com/artifact/io.codemine.java/rich-pg)

Reach Postgres with a rich Java client.

Connection pooling, resilient transactions, tracing, metrics and healthchecks - all under a simple interface for executing statements and transactions.

## Motivation

Every generated or hand-written PostgreSQL client ends up rebuilding the same
non-domain plumbing: a connection pool, retry-on-serialization-failure
transactions, slow-query logging, and OpenTelemetry traces/metrics for each
statement and transaction. `rich-pg` is that plumbing, built once, so
callers can focus on their own statements and domain types.

## Features

- **Connection pooling** via HikariCP, configured from a single `SessionSettings` record
- **Resilient transactions** - automatic retry on serialization failures and
  deadlocks (SQLSTATE `40001`, `40P01`, `23505`), with configurable isolation
  level and read-only flag
- **Statement execution** built on `io.codemine.java.postgresql:jdbc`'s
  `Statement<R>` abstraction
- **OpenTelemetry tracing** - a `CLIENT` span per statement/batch, an
  `INTERNAL` span per transaction, a `session.close` span, and a
  `healthCheck` span
- **OpenTelemetry metrics** - `db.client.operation.duration` histogram,
  `pgenie.transaction.retries` counter, and `pgenie.pool.connections.*`
  gauges (active/idle/pending/total)
- **SLF4J logging** - session open/close lifecycle, slow-query warnings, and
  transaction-exhausted-retries warnings
- **Health checks** and **graceful, idempotent shutdown** with a bounded
  connection-drain deadline

## Installation

The package is published to Maven Central under
[`io.codemine.java:rich-pg`](https://central.sonatype.com/artifact/io.codemine.java/rich-pg).

```xml
<dependency>
    <groupId>io.codemine.java</groupId>
    <artifactId>rich-pg</artifactId>
    <version>1.0.0</version>
</dependency>
```

This module depends on [`io.codemine.java.postgresql:jdbc`](https://github.com/codemine-io/postgresql-jdbc.java) for the `Statement<R>`/`Transaction<R>` abstractions it executes.

## Usage

### Opening a session

```java
import io.codemine.java.richpg.SessionSettings;
import io.codemine.java.richpg.Session;

SessionSettings config = SessionSettings
        .defaults("jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres")
        .withMaximumPoolSize(20)
        .withPoolName("my-service-pool")
        .withArtifactName("my-service");

try (Session session = new Session(config)) {
    // ...
}
```

`SessionSettings.defaults(jdbcUrl, user, password)` returns a config with a
pool size of 10, a 30-second connection/statement timeout, 3 transaction and statement
retry attempts, a 1-second slow-query-log threshold, a 2-second health-check timeout,
a 10-second close drain deadline, and the global `OpenTelemetry` instance - override
any of it with the `with*` methods shown above.

### Executing a statement

```java
import io.codemine.java.postgresql.jdbc.Statement;

record SelectOne() implements Statement<Integer> {
    public String sql() { return "select 1"; }
    // ... parameter binding / result decoding
}

int result = session.execute(new SelectOne());
```

### Executing a transaction

```java
int updated = session.executeTransaction(context -> {
    context.execute(new UpdateSomething(...));
    return context.execute(new SelectSomething(...));
});
```

Transactions default to `SERIALIZABLE` isolation, read-write, and
the configured retry attempts from `SessionSettings`; pass an explicit
`TransactionSettings` to `executeTransaction(transaction, settings)` to
override isolation level or read-only flag per call.

### Health checks and shutdown

```java
boolean healthy = session.healthCheck(); // round-trips "select 1" with a 2s timeout

session.close(); // idempotent; drains active connections up to a 10s deadline
```

## Observability

Every span/metric/log site below is stable, locked API surface - not an
implementation detail:

| Kind | Name | Notes |
|---|---|---|
| Span (`CLIENT`) | statement name (default: generated class's simple name) | `db.system.name`, `db.query.text`, `pgenie.statement.name`, `pgenie.db.user`, optional `db.operation.name`/`db.collection.name` |
| Span (`CLIENT`) | `batch` | as above, plus `db.operation.batch.size` |
| Span (`INTERNAL`) | `transaction` | isolation level, max attempts, read-only, attempt count, outcome |
| Span (`CLIENT`) | `healthCheck` | `db.system.name` only |
| Span (`INTERNAL`) | `session.close` | `pgenie.session.close.connections_remaining` |
| Metric (histogram, `s`) | `db.client.operation.duration` | per statement/batch |
| Metric (counter) | `pgenie.transaction.retries` | undimensioned |
| Metric (gauge) | `pgenie.pool.connections.{active,idle,pending,total}` | tagged `pool.name` |
| Log (`info`) | session opened / closing / closed | password redacted from the JDBC URL |
| Log (`warn`) | slow query detected | when a statement exceeds `slowQueryLogThreshold` |
| Log (`warn`) | transaction exhausted N attempts | only on `retries_exhausted` |

## Development

```bash
mvn verify
```

Runs unit tests (Surefire) and Testcontainers-backed integration tests
(Failsafe) against a real PostgreSQL instance.

## License

MIT - see [`LICENSE`](LICENSE).
