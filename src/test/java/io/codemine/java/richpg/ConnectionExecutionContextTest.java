package io.codemine.java.richpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** Unit tests for {@link ConnectionExecutionContext}. */
public class ConnectionExecutionContextTest {

  @Test
  void ofRejectsNullConnection() {
    var thrown =
        assertThrows(NullPointerException.class, () -> new ConnectionExecutionContext(null));
    assertEquals("connection", thrown.getMessage());
  }

  @Test
  void executeStatementDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    Connection connection = recordingConnection(handler);
    ConnectionExecutionContext context = new ConnectionExecutionContext(connection);
    FakeStatement statement = new FakeStatement();

    String result = context.execute(statement);

    assertEquals("ok", result);
    assertSame(connection, statement.seenConnection);
  }

  @Test
  void executeBatchRejectsEmptyIterable() {
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(new RecordingHandler()));

    var thrown =
        assertThrows(IllegalArgumentException.class, () -> context.executeBatch(List.of()));

    assertEquals("Batch must not be empty", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsNullIterable() {
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(new RecordingHandler()));

    var thrown = assertThrows(NullPointerException.class, () -> context.executeBatch(null));
    assertEquals("statements", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsNullStatement() {
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(new RecordingHandler()));
    List<Statement<Void>> statements = new ArrayList<>();
    statements.add(new FakeUpdateStatement());
    statements.add(null);

    var thrown = assertThrows(NullPointerException.class, () -> context.executeBatch(statements));
    assertEquals("statement", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsRowsReturningStatement() {
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(new RecordingHandler()));

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> context.executeBatch(List.of(new FakeRowsReturningStatement())));

    assertEquals("Batch execution is only supported for update statements", thrown.getMessage());
  }

  @Test
  void executeBatchRejectsMismatchedSql() {
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(new RecordingHandler()));

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
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(new RecordingHandler()));

    var thrown =
        assertThrows(
            NullPointerException.class,
            () -> context.executeBatch(List.of(new FakeUpdateStatement(null))));

    assertEquals("sql", thrown.getMessage());
  }

  @Test
  void setSavepointDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    Savepoint savepoint = throwingSavepoint();
    handler.returnValue = savepoint;
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(handler));

    Savepoint result = context.setSavepoint();

    assertSame(savepoint, result);
    assertEquals(List.of("setSavepoint"), handler.invokedMethods);
  }

  @Test
  void rollbackToSavepointDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(handler));
    Savepoint savepoint = throwingSavepoint();

    context.rollback(savepoint);

    assertEquals(List.of("rollback"), handler.invokedMethods);
  }

  @Test
  void releaseSavepointDelegatesToConnection() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    ConnectionExecutionContext context =
        new ConnectionExecutionContext(recordingConnection(handler));
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
