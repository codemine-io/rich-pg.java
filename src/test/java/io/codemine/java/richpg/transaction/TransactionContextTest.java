package io.codemine.java.richpg.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.Statement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TransactionContext}. */
public class TransactionContextTest {

  @Test
  void ofRejectsNullConnection() {
    var thrown = assertThrows(NullPointerException.class, () -> TransactionContext.of(null));
    assertEquals("connection", thrown.getMessage());
  }

  @Test
  void executeStatementDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    Connection connection = recordingConnection(handler);
    TransactionContext context = TransactionContext.of(connection);
    FakeStatement statement = new FakeStatement();

    String result = context.execute(statement);

    assertEquals("ok", result);
    assertSame(connection, statement.seenConnection);
  }

  @Test
  void executeBatchEmptyReturnsEmptyList() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    List<Void> result = context.executeBatch(List.of());

    assertTrue(result.isEmpty());
  }

  @Test
  void executeBatchRejectsNullIterable() {
    TransactionContext context = TransactionContext.of(recordingConnection(new RecordingHandler()));

    var thrown = assertThrows(NullPointerException.class, () -> context.executeBatch(null));
    assertEquals("statements", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsNullStatement() {
    TransactionContext context = TransactionContext.of(recordingConnection(new RecordingHandler()));
    List<Statement<Void>> statements = new ArrayList<>();
    statements.add(new FakeUpdateStatement());
    statements.add(null);

    var thrown = assertThrows(NullPointerException.class, () -> context.executeBatch(statements));
    assertEquals("statement", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsRowsReturningStatement() {
    TransactionContext context = TransactionContext.of(recordingConnection(new RecordingHandler()));

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> context.executeBatch(List.of(new FakeRowsReturningStatement())));

    assertEquals("Batch execution is only supported for update statements", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsMismatchedSql() {
    TransactionContext context = TransactionContext.of(recordingConnection(new RecordingHandler()));

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                context.executeBatch(
                    List.of(
                        new FakeUpdateStatement("UPDATE a SET x = ?"),
                        new FakeUpdateStatement("UPDATE b SET y = ?"))));

    assertEquals("All batch statements must use the same SQL text", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsNullSql() {
    TransactionContext context = TransactionContext.of(recordingConnection(new RecordingHandler()));

    var thrown =
        assertThrows(
            NullPointerException.class,
            () -> context.executeBatch(List.of(new FakeUpdateStatement(null))));

    assertEquals("sql", thrown.getMessage());
  }

  @Test
  void getAutoCommitDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    handler.returnValue = true;
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    boolean result = context.getAutoCommit();

    assertTrue(result);
    assertEquals(List.of("getAutoCommit"), handler.invokedMethods);
  }

  @Test
  void setAutoCommitDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    context.setAutoCommit(true);

    assertEquals(List.of("setAutoCommit"), handler.invokedMethods);
  }

  @Test
  void getTransactionIsolationDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    handler.returnValue = Connection.TRANSACTION_SERIALIZABLE;
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    int result = context.getTransactionIsolation();

    assertEquals(Connection.TRANSACTION_SERIALIZABLE, result);
    assertEquals(List.of("getTransactionIsolation"), handler.invokedMethods);
  }

  @Test
  void setTransactionIsolationDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    context.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

    assertEquals(List.of("setTransactionIsolation"), handler.invokedMethods);
  }

  @Test
  void isReadOnlyDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    handler.returnValue = true;
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    boolean result = context.isReadOnly();

    assertTrue(result);
    assertEquals(List.of("isReadOnly"), handler.invokedMethods);
  }

  @Test
  void setReadOnlyDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    context.setReadOnly(true);

    assertEquals(List.of("setReadOnly"), handler.invokedMethods);
  }

  @Test
  void commitDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    context.commit();

    assertEquals(List.of("commit"), handler.invokedMethods);
  }

  @Test
  void rollbackDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    context.rollback();

    assertEquals(List.of("rollback"), handler.invokedMethods);
  }

  @Test
  void setSavepointDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    Savepoint savepoint = throwingSavepoint();
    handler.returnValue = savepoint;
    TransactionContext context = TransactionContext.of(recordingConnection(handler));

    Savepoint result = context.setSavepoint();

    assertSame(savepoint, result);
    assertEquals(List.of("setSavepoint"), handler.invokedMethods);
  }

  @Test
  void rollbackToSavepointDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));
    Savepoint savepoint = throwingSavepoint();

    context.rollback(savepoint);

    assertEquals(List.of("rollback"), handler.invokedMethods);
  }

  @Test
  void releaseSavepointDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    TransactionContext context = TransactionContext.of(recordingConnection(handler));
    Savepoint savepoint = throwingSavepoint();

    context.releaseSavepoint(savepoint);

    assertEquals(List.of("releaseSavepoint"), handler.invokedMethods);
  }

  private static Connection recordingConnection(RecordingHandler handler) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  private static Savepoint throwingSavepoint() {
    return (Savepoint)
        Proxy.newProxyInstance(
            Savepoint.class.getClassLoader(),
            new Class<?>[] {Savepoint.class},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** Records invoked method names and returns a fixed value for all of them. */
  private static final class RecordingHandler implements InvocationHandler {
    final List<String> invokedMethods = new ArrayList<>();
    Object returnValue;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      invokedMethods.add(method.getName());
      return returnValue;
    }
  }

  private static final class FakeStatement implements Statement<String> {
    Connection seenConnection;

    @Override
    public String sql() {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public void bindParams(PreparedStatement ps) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public boolean returnsRows() {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public String decodeResultSet(ResultSet rs) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public String decodeAffectedRows(long affectedRows) {
      throw new UnsupportedOperationException("Not used");
    }

    @Override
    public String execute(Connection conn) {
      this.seenConnection = conn;
      return "ok";
    }
  }

  private static class FakeUpdateStatement implements Statement<Void> {
    private final String sql;

    FakeUpdateStatement() {
      this("UPDATE table SET value = ?");
    }

    FakeUpdateStatement(String sql) {
      this.sql = sql;
    }

    @Override
    public String sql() {
      return sql;
    }

    @Override
    public void bindParams(PreparedStatement ps) {
      throw new UnsupportedOperationException("Not used");
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

  private static final class FakeRowsReturningStatement extends FakeUpdateStatement {
    FakeRowsReturningStatement() {
      super("SELECT id FROM table WHERE id = ?");
    }

    @Override
    public boolean returnsRows() {
      return true;
    }
  }
}
