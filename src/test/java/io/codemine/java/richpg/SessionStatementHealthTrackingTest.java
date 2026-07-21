package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.codemine.java.postgresql.jdbc.Statement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the passive per-statement-class health tracking described in ADR 0001: real
 * statement executions feed {@link Session#healthCheck()} in addition to the active probe.
 */
class SessionStatementHealthTrackingTest {

  private HikariDataSource dataSource;
  private SessionSettings settings;
  private Connection connection;
  private PreparedStatement preparedStatement;

  @BeforeEach
  void setUp() throws SQLException {
    HikariPoolMXBean pool = Mockito.mock(HikariPoolMXBean.class);
    dataSource = Mockito.mock(HikariDataSource.class);
    Mockito.when(dataSource.getHikariPoolMXBean()).thenReturn(pool);
    Mockito.doNothing().when(dataSource).close();
    settings = SessionSettings.defaults("jdbc:postgresql://h/db", "u", "p").withRetryAttempts(1);

    connection = Mockito.mock(Connection.class);
    preparedStatement = Mockito.mock(PreparedStatement.class);
    Mockito.when(dataSource.getConnection()).thenReturn(connection);
  }

  private static Statement<String> updateStatement() {
    Statement<String> s = Mockito.mock(Statement.class);
    Mockito.when(s.statementName()).thenReturn("updateThing");
    Mockito.when(s.sql()).thenReturn("update thing set x = 1");
    Mockito.when(s.returnsRows()).thenReturn(false);
    Mockito.when(s.operationName()).thenReturn(Optional.empty());
    Mockito.when(s.collectionName()).thenReturn(Optional.empty());
    return s;
  }

  @Test
  void undefinedColumnDuringExecutionMarksStatementBroken() throws SQLException {
    Statement<String> statement = updateStatement();
    Mockito.when(connection.prepareStatement("update thing set x = 1"))
        .thenReturn(preparedStatement);
    Mockito.when(preparedStatement.executeUpdate())
        .thenThrow(new SQLException("column \"x\" does not exist", "42703"));

    Session session = new Session(settings, dataSource);
    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);

      assertThat(session.healthCheck().brokenStatementClasses()).contains(statement.getClass());
    } finally {
      session.close();
    }
  }

  @Test
  void uniqueViolationDuringExecutionDoesNotMarkStatementBroken() throws SQLException {
    Statement<String> statement = updateStatement();
    Mockito.when(connection.prepareStatement("update thing set x = 1"))
        .thenReturn(preparedStatement);
    Mockito.when(preparedStatement.executeUpdate())
        .thenThrow(new SQLException("duplicate key", "23505"));

    Session session = new Session(settings, dataSource);
    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);

      assertThat(session.healthCheck().brokenStatementClasses())
          .doesNotContain(statement.getClass());
    } finally {
      session.close();
    }
  }

  @Test
  void decodeFailureMarksStatementBrokenEvenWhenUnclassifiable() throws SQLException {
    Statement<String> statement = updateStatement();
    Mockito.when(connection.prepareStatement("update thing set x = 1"))
        .thenReturn(preparedStatement);
    Mockito.when(preparedStatement.executeUpdate()).thenReturn(1);
    Mockito.when(statement.decodeAffectedRows(1L))
        .thenThrow(new RuntimeException("shape mismatch"));

    Session session = new Session(settings, dataSource);
    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(RuntimeException.class);

      assertThat(session.healthCheck().brokenStatementClasses()).contains(statement.getClass());
    } finally {
      session.close();
    }
  }

  @Test
  void successfulExecutionAfterBrokenMarkClearsIt() throws SQLException {
    Statement<String> statement = updateStatement();
    Mockito.when(connection.prepareStatement("update thing set x = 1"))
        .thenReturn(preparedStatement);
    Mockito.when(preparedStatement.executeUpdate())
        .thenThrow(new SQLException("column \"x\" does not exist", "42703"))
        .thenReturn(1);
    Mockito.when(statement.decodeAffectedRows(1L)).thenReturn("ok");

    Session session = new Session(settings, dataSource);
    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);
      assertThat(session.healthCheck().brokenStatementClasses()).contains(statement.getClass());

      String result = session.execute(statement);

      assertThat(result).isEqualTo("ok");
      assertThat(session.healthCheck().brokenStatementClasses())
          .doesNotContain(statement.getClass());
    } finally {
      session.close();
    }
  }

  private static Statement<String> selectStatement() {
    Statement<String> s = Mockito.mock(Statement.class);
    Mockito.when(s.statementName()).thenReturn("selectThing");
    Mockito.when(s.sql()).thenReturn("select 1");
    Mockito.when(s.returnsRows()).thenReturn(true);
    Mockito.when(s.operationName()).thenReturn(Optional.empty());
    Mockito.when(s.collectionName()).thenReturn(Optional.empty());
    return s;
  }

  @Test
  void undefinedTableDuringRowReturningExecutionMarksStatementBroken() throws SQLException {
    Statement<String> statement = selectStatement();
    Mockito.when(connection.prepareStatement("select 1")).thenReturn(preparedStatement);
    Mockito.when(preparedStatement.execute())
        .thenThrow(new SQLException("relation does not exist", "42P01"));

    Session session = new Session(settings, dataSource);
    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);

      assertThat(session.healthCheck().brokenStatementClasses()).contains(statement.getClass());
    } finally {
      session.close();
    }
  }

  @Test
  void decodeFailureOnRowReturningStatementMarksBroken() throws SQLException {
    Statement<String> statement = selectStatement();
    ResultSet resultSet = Mockito.mock(ResultSet.class);
    Mockito.when(connection.prepareStatement("select 1")).thenReturn(preparedStatement);
    Mockito.when(preparedStatement.execute()).thenReturn(true);
    Mockito.when(preparedStatement.getResultSet()).thenReturn(resultSet);
    Mockito.when(statement.decodeResultSet(resultSet))
        .thenThrow(new SQLException("shape mismatch", "22000"));

    Session session = new Session(settings, dataSource);
    try {
      assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(SQLException.class);

      assertThat(session.healthCheck().brokenStatementClasses()).contains(statement.getClass());
    } finally {
      session.close();
    }
  }
}
