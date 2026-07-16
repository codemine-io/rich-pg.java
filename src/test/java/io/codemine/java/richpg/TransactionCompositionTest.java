package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionCompositionTest {

  private static ExecutionContext fakeContext(List<Object> log) {
    return new ExecutionContext() {
      @Override
      public <R> R execute(Statement<R> statement) {
        log.add("execute");
        return null;
      }

      @Override
      public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) {
        return List.of();
      }

      @Override
      public Savepoint setSavepoint() {
        return null;
      }

      @Override
      public void rollback(Savepoint savepoint) {}

      @Override
      public void releaseSavepoint(Savepoint savepoint) {}
    };
  }

  @Test
  void andThenRunsBothInOrder() throws SQLException {
    List<Object> log = new java.util.ArrayList<>();
    Transaction<String> first =
        ctx -> {
          log.add("first");
          return "a";
        };
    Transaction<String> second =
        ctx -> {
          log.add("second");
          return "b";
        };
    String result = first.andThen(second).run(fakeContext(log));
    assertThat(result).isEqualTo("b");
    assertThat(log).containsExactly("first", "second");
  }

  @Test
  void mapTransformsResult() throws SQLException {
    Transaction<Integer> t = ctx -> 41;
    Integer result = t.map(x -> x + 1).run(fakeContext(new java.util.ArrayList<>()));
    assertThat(result).isEqualTo(42);
  }

  @Test
  void flatMapSequencesUsingResult() throws SQLException {
    Transaction<Integer> t = ctx -> 2;
    Transaction<Integer> composed = t.flatMap(x -> ctx -> x * 10);
    assertThat(composed.run(fakeContext(new java.util.ArrayList<>()))).isEqualTo(20);
  }

  @Test
  void emptyAlwaysThrows() {
    Transaction<String> t = Transaction.empty();
    assertThatThrownBy(() -> t.run(fakeContext(new java.util.ArrayList<>())))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void firstOfRunsFirstSuccessfulAlternative() throws SQLException {
    ExecutionContext ctx = mock(ExecutionContext.class);
    when(ctx.setSavepoint()).thenReturn(mock(Savepoint.class));
    Transaction<String> failing =
        c -> {
          throw new SQLException("nope", "22000");
        };
    Transaction<String> succeeding = c -> "ok";
    String result = Transaction.firstOf(failing, succeeding).run(ctx);
    assertThat(result).isEqualTo("ok");
    // firstOf reduces via Transaction.empty().or(failing).or(succeeding), so the empty()
    // alternative's own immediate failure also goes through the savepoint-rollback path once,
    // in addition to `failing`'s — two rollbacks total, not one.
    verify(ctx, times(2)).rollback(any(Savepoint.class));
  }

  @Test
  void orRethrowsSerializationFailureUntouched() throws SQLException {
    ExecutionContext ctx = mock(ExecutionContext.class);
    when(ctx.setSavepoint()).thenReturn(mock(Savepoint.class));
    Transaction<String> failing =
        c -> {
          throw new SQLException("conflict", "40001");
        };
    Transaction<String> alternative = c -> "should not run";
    assertThatThrownBy(() -> failing.or(alternative).run(ctx))
        .isInstanceOf(SQLException.class)
        .hasMessage("conflict");
    verify(ctx, never()).rollback(any(Savepoint.class));
  }

  @Test
  void orFallsBackOnUniqueViolation() throws SQLException {
    ExecutionContext ctx = mock(ExecutionContext.class);
    when(ctx.setSavepoint()).thenReturn(mock(Savepoint.class));
    Transaction<String> failing =
        c -> {
          throw new SQLException("dup", "23505");
        };
    Transaction<String> alternative = c -> "fallback";
    assertThat(failing.or(alternative).run(ctx)).isEqualTo("fallback");
    verify(ctx).rollback(any(Savepoint.class));
  }
}
