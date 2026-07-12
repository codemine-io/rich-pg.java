package io.codemine.java.reachpg;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a live PostgreSQL database.
 *
 * <p>Provides a shared {@link PostgreSQLContainer} and helper methods to open connections and
 * manage a simple {@code retry_counter} table used by transaction tests.
 */
abstract class AbstractDatabaseIT {

  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:18").withCommand("postgres", "-c", "max_connections=200");

  @BeforeAll
  static void startContainer() {
    PG.start();
  }

  @BeforeEach
  void createCounterTable() throws SQLException {
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "create table if not exists retry_counter (id int primary key, value int not null)")) {
      ps.execute();
    }
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into retry_counter (id, value) values (1, 0) on conflict (id) do update set value = 0")) {
      ps.executeUpdate();
    }
  }

  @AfterEach
  void dropCounterTable() throws SQLException {
    try (Connection conn = openConnection();
        PreparedStatement ps = conn.prepareStatement("drop table if exists retry_counter")) {
      ps.execute();
    }
  }

  Connection openConnection() throws SQLException {
    return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }
}
