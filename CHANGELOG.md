# Upcoming

## Breaking

- Move `Transaction`, `TransactionSettings`, `TransactionContext`, `ExecutionContext`, and `IsolationLevel` from `postgresql-jdbc`'s `io.codemine.java.postgresql.jdbc` package into a new `io.codemine.java.richpg.transaction` package.
- Rename `Transaction.executeOn` to `Transaction.execute`.

## Non-breaking

- Initial extraction from `pgenie-io/java.gen-design`'s `vendor/rich-client`, rebranded as `io.codemine.java:rich-pg` (package `io.codemine.java.richpg`, config class `RichPgConfig`).
- Session management, HikariCP-backed connection pooling, resilient transaction retry, statement execution, and OpenTelemetry/SLF4J observability for PostgreSQL JDBC clients.
- Add individual statement-level retries alongside transaction-level retries.
