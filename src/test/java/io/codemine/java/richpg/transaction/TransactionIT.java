package io.codemine.java.richpg.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.codemine.java.postgresql.jdbc.Statement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/** Integration tests for {@link Transaction}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TransactionIT {

  private static final String TABLE_NAME = "transaction_it";

  static final PostgreSQLContainer<?> container;
  static final HikariDataSource jdbcPool;

  static {
    container =
        new PostgreSQLContainer<>("postgres:18").withCommand("postgres -c max_connections=300");
    container.start();

    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(container.getJdbcUrl());
    hikariConfig.setUsername(container.getUsername());
    hikariConfig.setPassword(container.getPassword());
    hikariConfig.addDataSourceProperty("prepareThreshold", "0");
    hikariConfig.setMaximumPoolSize(10);
    jdbcPool = new HikariDataSource(hikariConfig);

    Runtime.getRuntime().addShutdownHook(new Thread(jdbcPool::close));
  }

  @SuppressWarnings("unused")
  @BeforeEach
  void resetTable() throws SQLException {
    try (var conn = jdbcPool.getConnection();
        var ps = conn.createStatement()) {
      ps.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
      ps.executeUpdate(
          "CREATE TABLE " + TABLE_NAME + " (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
    }
  }

  @Test
  void executeCommitsOnSuccessfulRun() throws Exception {
    Transaction<Void> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          context.execute(new InsertRow(2, "two"));
          return null;
        };

    try (var conn = jdbcPool.getConnection()) {
      transaction.execute(TransactionContext.of(conn));
    }

    assertRowCount(2);
  }

  @Test
  void executeRollsBackOnSqlExceptionFromRun() throws Exception {
    Transaction<Void> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          throw new SQLException("simulated failure");
        };

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(SQLException.class, () -> transaction.execute(TransactionContext.of(conn)));
    }

    assertRowCount(0);
  }

  @Test
  void executeRollsBackOnRuntimeExceptionFromRun() throws Exception {
    Transaction<Void> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          throw new IllegalStateException("simulated failure");
        };

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(
          IllegalStateException.class, () -> transaction.execute(TransactionContext.of(conn)));
    }

    assertRowCount(0);
  }

  @Test
  void executeRollsBackAndRestoresAutoCommitWhenRunThrowsError() throws Exception {
    Transaction<Void> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          throw new AssertionError("simulated error escaping the retry loop");
        };

    try (var conn = jdbcPool.getConnection()) {
      conn.setAutoCommit(true);
      assertThrows(AssertionError.class, () -> transaction.execute(TransactionContext.of(conn)));
      assertTrue(conn.getAutoCommit());
    }

    assertRowCount(0);
  }

  @Test
  void executePreservesOriginalFailureWhenRestoringConnectionStateAlsoFails() throws Exception {
    SQLException restoreFailure = new SQLException("simulated restore failure");
    Transaction<Void> transaction =
        context -> {
          throw new SQLException("original failure", "23503");
        };

    try (var conn = jdbcPool.getConnection()) {
      Connection faulty = faultyOnNthCall(conn, "setReadOnly", 2, restoreFailure);
      SQLException thrown =
          assertThrows(
              SQLException.class, () -> transaction.execute(TransactionContext.of(faulty)));
      assertEquals("original failure", thrown.getMessage());
      assertTrue(List.of(thrown.getSuppressed()).contains(restoreFailure));
    }
  }

  /**
   * Wraps {@code delegate} so that the {@code count}-th invocation of the method named {@code
   * methodName} throws {@code fault} instead of delegating.
   */
  private static Connection faultyOnNthCall(
      Connection delegate, String methodName, int count, SQLException fault) {
    AtomicInteger calls = new AtomicInteger();
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getName().equals(methodName) && calls.incrementAndGet() == count) {
            throw fault;
          }
          try {
            return method.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  @Test
  void executeRestoresOriginalAutoCommitAfterSuccess() throws Exception {
    Transaction<Void> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return null;
        };

    try (var conn = jdbcPool.getConnection()) {
      conn.setAutoCommit(true);
      transaction.execute(TransactionContext.of(conn));
      assertTrue(conn.getAutoCommit());
    }
  }

  @Test
  void executeRestoresOriginalAutoCommitAfterFailure() throws Exception {
    Transaction<Void> transaction =
        context -> {
          throw new SQLException("simulated failure");
        };

    try (var conn = jdbcPool.getConnection()) {
      conn.setAutoCommit(true);
      assertThrows(SQLException.class, () -> transaction.execute(TransactionContext.of(conn)));
      assertTrue(conn.getAutoCommit());
    }
  }

  @Test
  void executeRejectsNullConnection() {
    Transaction<Void> transaction = context -> null;

    var thrown = assertThrows(NullPointerException.class, () -> transaction.execute(null));
    assertEquals("context", thrown.getMessage());
  }

  @Test
  void ofAdaptsStatementIntoTransaction() throws Exception {
    Transaction<Void> transaction = Transaction.of(new InsertRow(1, "one"));

    try (var conn = jdbcPool.getConnection()) {
      transaction.execute(TransactionContext.of(conn));
    }

    assertRowCount(1);
  }

  @Test
  void executeAppliesIsolationLevelFromSettings() throws Exception {
    int[] observedIsolation = new int[1];
    Transaction<Void> transaction = context -> null;
    TransactionContext recordingContext = recordingTransactionContext(observedIsolation, null);

    transaction.execute(
        recordingContext,
        TransactionSettings.SERIALIZABLE_WRITE.withIsolationLevel(IsolationLevel.SERIALIZABLE));

    assertEquals(Connection.TRANSACTION_SERIALIZABLE, observedIsolation[0]);
  }

  @Test
  void executeRestoresOriginalIsolationLevelAfterExecute() throws Exception {
    Transaction<Void> transaction = context -> null;

    try (var conn = jdbcPool.getConnection()) {
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      transaction.execute(
          TransactionContext.of(conn),
          TransactionSettings.SERIALIZABLE_WRITE.withIsolationLevel(IsolationLevel.SERIALIZABLE));
      assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation());
    }
  }

  @Test
  void executeAppliesReadOnlyFromSettings() throws Exception {
    boolean[] observedReadOnly = new boolean[1];
    Transaction<Void> transaction = context -> null;
    TransactionContext recordingContext = recordingTransactionContext(null, observedReadOnly);

    transaction.execute(
        recordingContext, TransactionSettings.SERIALIZABLE_WRITE.withReadOnly(true));

    assertTrue(observedReadOnly[0]);
  }

  @Test
  void executeRestoresOriginalReadOnlyAfterExecute() throws Exception {
    Transaction<Void> transaction = context -> null;

    try (var conn = jdbcPool.getConnection()) {
      conn.setReadOnly(false);
      transaction.execute(
          TransactionContext.of(conn), TransactionSettings.SERIALIZABLE_WRITE.withReadOnly(true));
      assertTrue(!conn.isReadOnly());
    }
  }

  @Test
  void executeRejectsNullSettings() throws Exception {
    Transaction<Void> transaction = context -> null;

    try (var conn = jdbcPool.getConnection()) {
      var thrown =
          assertThrows(
              NullPointerException.class,
              () -> transaction.execute(TransactionContext.of(conn), null));
      assertEquals("settings", thrown.getMessage());
    }
  }

  @Test
  void executeRetriesUntilSuccessOnRetryableSqlState() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    Transaction<Integer> transaction =
        context -> {
          int attempt = attempts.incrementAndGet();
          context.execute(new InsertRow(attempt, "attempt-" + attempt));
          if (attempt < 3) {
            throw new SQLException("simulated conflict", "40001");
          }
          return attempt;
        };

    int result;
    try (var conn = jdbcPool.getConnection()) {
      result =
          transaction.execute(
              TransactionContext.of(conn),
              TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(5));
    }

    assertEquals(3, result);
    assertEquals(3, attempts.get());
    // Failed attempts' inserts were rolled back; only the successful attempt's row survives.
    assertRowCount(1);
  }

  @Test
  void executeGivesUpAfterMaxAttempts() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    Transaction<Void> transaction =
        context -> {
          attempts.incrementAndGet();
          throw new SQLException("simulated conflict", "40001");
        };

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(
          SQLException.class,
          () ->
              transaction.execute(
                  TransactionContext.of(conn),
                  TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(2)));
    }

    assertEquals(2, attempts.get());
  }

  @Test
  void executeRetriesOnUniqueViolationSqlState() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    Transaction<Void> transaction =
        context -> {
          if (attempts.incrementAndGet() < 3) {
            throw new SQLException("unique violation", "23505");
          }
          return null;
        };

    try (var conn = jdbcPool.getConnection()) {
      transaction.execute(
          TransactionContext.of(conn), TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(5));
    }

    assertEquals(3, attempts.get());
  }

  @Test
  void executeDoesNotRetryNonRetryableSqlState() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    Transaction<Void> transaction =
        context -> {
          attempts.incrementAndGet();
          throw new SQLException("foreign key violation", "23503");
        };

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(
          SQLException.class,
          () ->
              transaction.execute(
                  TransactionContext.of(conn),
                  TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(5)));
    }

    assertEquals(1, attempts.get());
  }

  @Test
  void andThenCommitsBothStatementsAsOneTransaction() throws Exception {
    Transaction<Void> transaction =
        Transaction.of(new InsertRow(1, "one")).andThen(Transaction.of(new InsertRow(2, "two")));

    try (var conn = jdbcPool.getConnection()) {
      transaction.execute(TransactionContext.of(conn));
    }

    assertRowCount(2);
  }

  @Test
  void andThenRollsBackFirstStatementWhenSecondFails() throws Exception {
    Transaction<Void> failing =
        context -> {
          throw new SQLException("simulated failure");
        };
    Transaction<Void> transaction = Transaction.of(new InsertRow(1, "one")).andThen(failing);

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(SQLException.class, () -> transaction.execute(TransactionContext.of(conn)));
    }

    assertRowCount(0);
  }

  @Test
  void mapTransformsResult() throws Exception {
    Transaction<Integer> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return 1;
        };
    Transaction<String> mapped = transaction.map(count -> "count=" + count);

    String result;
    try (var conn = jdbcPool.getConnection()) {
      result = mapped.execute(TransactionContext.of(conn));
    }

    assertEquals("count=1", result);
  }

  @Test
  void flatMapComposesDependentTransactionsAsOneTransaction() throws Exception {
    Transaction<Integer> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return 1;
        };
    Transaction<String> composed =
        transaction.flatMap(
            id ->
                context -> {
                  context.execute(new InsertRow(id + 1, "two"));
                  return "id=" + id;
                });

    String result;
    try (var conn = jdbcPool.getConnection()) {
      result = composed.execute(TransactionContext.of(conn));
    }

    assertEquals("id=1", result);
    assertRowCount(2);
  }

  @Test
  void flatMapRollsBackFirstStatementWhenSecondFails() throws Exception {
    Transaction<Integer> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return 1;
        };
    Transaction<String> composed =
        transaction.flatMap(
            id ->
                context -> {
                  throw new SQLException("simulated failure");
                });

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(SQLException.class, () -> composed.execute(TransactionContext.of(conn)));
    }

    assertRowCount(0);
  }

  @Test
  void flatMapRejectsNullMapper() {
    Transaction<Integer> transaction = context -> 1;

    var thrown = assertThrows(NullPointerException.class, () -> transaction.flatMap(null));
    assertEquals("mapper", thrown.getMessage());
  }

  @Test
  void customTransactionContextInterceptsEachStatementExecution() throws Exception {
    AtomicInteger executedStatements = new AtomicInteger();
    Transaction<Void> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          context.execute(new InsertRow(2, "two"));
          return null;
        };

    try (var conn = jdbcPool.getConnection()) {
      TransactionContext delegate = TransactionContext.of(conn);
      TransactionContext countingContext =
          new TransactionContext() {
            @Override
            public <R> R execute(Statement<R> statement) throws SQLException {
              executedStatements.incrementAndGet();
              return delegate.execute(statement);
            }

            @Override
            public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements)
                throws SQLException {
              return delegate.executeBatch(statements);
            }

            @Override
            public boolean getAutoCommit() throws SQLException {
              return delegate.getAutoCommit();
            }

            @Override
            public void setAutoCommit(boolean autoCommit) throws SQLException {
              delegate.setAutoCommit(autoCommit);
            }

            @Override
            public int getTransactionIsolation() throws SQLException {
              return delegate.getTransactionIsolation();
            }

            @Override
            public void setTransactionIsolation(int level) throws SQLException {
              delegate.setTransactionIsolation(level);
            }

            @Override
            public boolean isReadOnly() throws SQLException {
              return delegate.isReadOnly();
            }

            @Override
            public void setReadOnly(boolean readOnly) throws SQLException {
              delegate.setReadOnly(readOnly);
            }

            @Override
            public void commit() throws SQLException {
              delegate.commit();
            }

            @Override
            public void rollback() throws SQLException {
              delegate.rollback();
            }

            @Override
            public Savepoint setSavepoint() throws SQLException {
              return delegate.setSavepoint();
            }

            @Override
            public void rollback(Savepoint savepoint) throws SQLException {
              delegate.rollback(savepoint);
            }

            @Override
            public void releaseSavepoint(Savepoint savepoint) throws SQLException {
              delegate.releaseSavepoint(savepoint);
            }
          };
      transaction.execute(countingContext);
    }

    assertEquals(2, executedStatements.get());
    assertRowCount(2);
  }

  @Test
  void orReturnsLeftResultWithoutRunningRightWhenLeftSucceeds() throws Exception {
    Transaction<Integer> left =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return 1;
        };
    Transaction<Integer> right =
        context -> {
          context.execute(new InsertRow(2, "two"));
          return 2;
        };

    int result;
    try (var conn = jdbcPool.getConnection()) {
      result = left.or(right).execute(TransactionContext.of(conn));
    }

    assertEquals(1, result);
    assertRowCount(1);
  }

  @Test
  void orRollsBackOnlyLeftEffectsWhenLeftFailsRecoverably() throws Exception {
    Transaction<Integer> left =
        context -> {
          context.execute(new InsertRow(2, "two"));
          throw new SQLException("unique violation", "23505");
        };
    Transaction<Integer> right =
        context -> {
          context.execute(new InsertRow(3, "three"));
          return 3;
        };
    Transaction<Integer> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return left.or(right).run(context);
        };

    int result;
    try (var conn = jdbcPool.getConnection()) {
      result = transaction.execute(TransactionContext.of(conn));
    }

    assertEquals(3, result);
    // Row 1 (before the or()) and row 3 (the right alternative) survive; row 2 (left's own,
    // failed effect) was rolled back to the savepoint, not the whole transaction.
    assertRowCount(2);
  }

  @Test
  void orFallsBackToAlternativeWhenLeftThrowsRuntimeException() throws Exception {
    Transaction<Integer> left =
        context -> {
          context.execute(new InsertRow(2, "two"));
          throw new IllegalStateException("simulated failure");
        };
    Transaction<Integer> right =
        context -> {
          context.execute(new InsertRow(3, "three"));
          return 3;
        };
    Transaction<Integer> transaction =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return left.or(right).run(context);
        };

    int result;
    try (var conn = jdbcPool.getConnection()) {
      result = transaction.execute(TransactionContext.of(conn));
    }

    assertEquals(3, result);
    // Row 1 (before the or()) and row 3 (the right alternative) survive; row 2 (left's own,
    // failed effect) was rolled back to the savepoint, not the whole transaction.
    assertRowCount(2);
  }

  @Test
  void orReleasesSavepointAfterRollingBackOnFallback() throws Exception {
    AtomicInteger releaseSavepointCalls = new AtomicInteger();
    Transaction<Integer> left =
        context -> {
          throw new SQLException("unique violation", "23505");
        };
    Transaction<Integer> right = context -> 2;

    try (var conn = jdbcPool.getConnection()) {
      Connection counting = countingCalls(conn, "releaseSavepoint", releaseSavepointCalls);
      left.or(right).execute(TransactionContext.of(counting));
    }

    assertEquals(
        1,
        releaseSavepointCalls.get(),
        "the savepoint rolled back to on fallback must also be released, "
            + "otherwise it lingers for the rest of the transaction");
  }

  /**
   * Returns a {@link TransactionContext} that records {@code setTransactionIsolation} and {@code
   * setReadOnly} calls into {@code observedIsolation} and {@code observedReadOnly} respectively.
   * Either array may be {@code null} if the corresponding value does not need to be captured.
   */
  private static TransactionContext recordingTransactionContext(
      int[] observedIsolation, boolean[] observedReadOnly) {
    return new TransactionContext() {
      private int isolation = Connection.TRANSACTION_READ_COMMITTED;
      private boolean readOnly = false;
      private int isolationSets;
      private int readOnlySets;

      @Override
      public <R> R execute(Statement<R> statement) {
        throw new UnsupportedOperationException("Not used");
      }

      @Override
      public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) {
        throw new UnsupportedOperationException("Not used");
      }

      @Override
      public boolean getAutoCommit() {
        return true;
      }

      @Override
      public void setAutoCommit(boolean autoCommit) {}

      @Override
      public int getTransactionIsolation() {
        return isolation;
      }

      @Override
      public void setTransactionIsolation(int level) {
        if (isolationSets == 0 && observedIsolation != null) {
          observedIsolation[0] = level;
        }
        isolationSets++;
        isolation = level;
      }

      @Override
      public boolean isReadOnly() {
        return readOnly;
      }

      @Override
      public void setReadOnly(boolean value) {
        if (readOnlySets == 0 && observedReadOnly != null) {
          observedReadOnly[0] = value;
        }
        readOnlySets++;
        readOnly = value;
      }

      @Override
      public void commit() {}

      @Override
      public void rollback() {}

      @Override
      public Savepoint setSavepoint() {
        throw new UnsupportedOperationException("Not used");
      }

      @Override
      public void rollback(Savepoint savepoint) {}

      @Override
      public void releaseSavepoint(Savepoint savepoint) {}
    };
  }

  /** Wraps {@code delegate}, counting invocations of the method named {@code methodName}. */
  private static Connection countingCalls(
      Connection delegate, String methodName, AtomicInteger counter) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getName().equals(methodName)) {
            counter.incrementAndGet();
          }
          try {
            return method.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  @Test
  void orRethrowsSerializationFailureWithoutRunningRight() throws Exception {
    AtomicInteger rightRuns = new AtomicInteger();
    Transaction<Integer> left =
        context -> {
          context.execute(new InsertRow(1, "one"));
          throw new SQLException("serialization failure", "40001");
        };
    Transaction<Integer> right =
        context -> {
          rightRuns.incrementAndGet();
          return 2;
        };

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(SQLException.class, () -> left.or(right).execute(TransactionContext.of(conn)));
    }

    assertEquals(0, rightRuns.get());
  }

  @Test
  void orRethrowsDeadlockDetectedWithoutRunningRight() throws Exception {
    AtomicInteger rightRuns = new AtomicInteger();
    Transaction<Integer> left =
        context -> {
          throw new SQLException("deadlock detected", "40P01");
        };
    Transaction<Integer> right =
        context -> {
          rightRuns.incrementAndGet();
          return 2;
        };

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(SQLException.class, () -> left.or(right).execute(TransactionContext.of(conn)));
    }

    assertEquals(0, rightRuns.get());
  }

  @Test
  void orAddsOriginalFailureAsSuppressedWhenAlternativeAlsoFails() throws Exception {
    Transaction<Integer> left =
        context -> {
          throw new SQLException("left failed", "23505");
        };
    Transaction<Integer> right =
        context -> {
          throw new SQLException("right failed", "23503");
        };

    try (var conn = jdbcPool.getConnection()) {
      SQLException thrown =
          assertThrows(
              SQLException.class, () -> left.or(right).execute(TransactionContext.of(conn)));
      assertEquals("right failed", thrown.getMessage());
      assertEquals(1, thrown.getSuppressed().length);
      assertEquals("left failed", thrown.getSuppressed()[0].getMessage());
    }
  }

  @Test
  void emptyOrXBehavesAsX() throws Exception {
    Transaction<Integer> x =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return 1;
        };

    int result;
    try (var conn = jdbcPool.getConnection()) {
      result = Transaction.<Integer>empty().or(x).execute(TransactionContext.of(conn));
    }

    assertEquals(1, result);
    assertRowCount(1);
  }

  @Test
  void firstOfReturnsFirstSuccessAmongFailingAlternatives() throws Exception {
    Transaction<Integer> a =
        context -> {
          throw new SQLException("unique violation", "23505");
        };
    Transaction<Integer> b =
        context -> {
          throw new SQLException("unique violation", "23505");
        };
    Transaction<Integer> c =
        context -> {
          context.execute(new InsertRow(1, "one"));
          return 3;
        };

    int result;
    try (var conn = jdbcPool.getConnection()) {
      result = Transaction.firstOf(List.of(a, b, c)).execute(TransactionContext.of(conn));
    }

    assertEquals(3, result);
    assertRowCount(1);
  }

  @Test
  void firstOfEmptyListFailsLazilyOnRun() throws Exception {
    Transaction<Integer> transaction = Transaction.firstOf(List.of());

    try (var conn = jdbcPool.getConnection()) {
      assertThrows(SQLException.class, () -> transaction.execute(TransactionContext.of(conn)));
    }
  }

  private static void assertRowCount(int expected) throws SQLException {
    try (var conn = jdbcPool.getConnection();
        var ps = conn.prepareStatement("SELECT COUNT(*) FROM " + TABLE_NAME);
        ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getInt(1));
    }
  }

  private record InsertRow(int id, String value) implements Statement<Void> {

    @Override
    public String sql() {
      return "INSERT INTO " + TABLE_NAME + " (id, value) VALUES (?, ?)";
    }

    @Override
    public void bindParams(PreparedStatement ps) throws SQLException {
      ps.setInt(1, id);
      ps.setString(2, value);
    }

    @Override
    public boolean returnsRows() {
      return false;
    }

    @Override
    public Void decodeResultSet(ResultSet rs) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public Void decodeAffectedRows(long affectedRows) {
      return null;
    }
  }
}
