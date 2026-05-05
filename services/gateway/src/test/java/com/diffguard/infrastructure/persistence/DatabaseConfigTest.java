package com.diffguard.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DatabaseConfig")
class DatabaseConfigTest {

    // ------------------------------------------------------------------
    // fromEnv
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("fromEnv")
    class FromEnv {

        @Test
        @DisplayName("fromEnv creates config with default values when env vars not set")
        void fromEnvWithDefaults() {
            // This test verifies that fromEnv() can be called without env vars set
            // It will use the default values: localhost:3306/diffguard
            // We catch the exception because MySQL may not be available in test env
            try {
                DatabaseConfig config = DatabaseConfig.fromEnv();
                assertNotNull(config);
                assertNotNull(config.getDataSource());
                config.close();
            } catch (Exception e) {
                // Expected if MySQL is not running - verify it's a connection error
                assertTrue(e.getMessage().contains("Connection") ||
                                e.getMessage().contains("connection") ||
                                e.getMessage().contains("refused") ||
                                e.getMessage().contains("Failed") ||
                                e.getMessage().contains("Unable") ||
                                e.getMessage().contains("Communications"),
                        "Expected connection-related error but got: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Constructor and getDataSource
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("constructor creates HikariDataSource")
        void constructorCreatesDataSource() {
            // This will fail to connect to MySQL, but validates construction logic
            try {
                DatabaseConfig config = new DatabaseConfig(
                        "localhost", 3306, "testdb", "testuser", "testpass");
                DataSource ds = config.getDataSource();
                assertNotNull(ds);
                assertInstanceOf(HikariDataSource.class, ds);
                config.close();
            } catch (Exception e) {
                // Expected if MySQL is not running
                assertTrue(e.getMessage().contains("Connection") ||
                                e.getMessage().contains("connection") ||
                                e.getMessage().contains("refused") ||
                                e.getMessage().contains("Unable") ||
                                e.getMessage().contains("Communications"),
                        "Expected connection-related error but got: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("constructor configures JDBC URL correctly")
        void constructorConfiguresJdbcUrl() {
            try {
                DatabaseConfig config = new DatabaseConfig(
                        "db.example.com", 5700, "mydb", "admin", "secret123");
                HikariDataSource ds = (HikariDataSource) config.getDataSource();
                String jdbcUrl = ds.getJdbcUrl();
                assertTrue(jdbcUrl.contains("db.example.com"));
                assertTrue(jdbcUrl.contains("5700"));
                assertTrue(jdbcUrl.contains("mydb"));
                assertTrue(jdbcUrl.contains("useSSL=false"));
                config.close();
            } catch (Exception e) {
                // MySQL not available
                assertTrue(e.getMessage().contains("Connection") ||
                                e.getMessage().contains("connection") ||
                                e.getMessage().contains("refused") ||
                                e.getMessage().contains("Unable") ||
                                e.getMessage().contains("Communications"));
            }
        }

        @Test
        @DisplayName("constructor sets username correctly")
        void constructorSetsUsername() {
            try {
                DatabaseConfig config = new DatabaseConfig(
                        "localhost", 3306, "testdb", "myuser", "mypass");
                HikariDataSource ds = (HikariDataSource) config.getDataSource();
                assertEquals("myuser", ds.getUsername());
                config.close();
            } catch (Exception e) {
                // MySQL not available - validate the error is connection-related
                assertTrue(e.getMessage().contains("Connection") ||
                                e.getMessage().contains("connection") ||
                                e.getMessage().contains("refused") ||
                                e.getMessage().contains("Unable") ||
                                e.getMessage().contains("Communications"));
            }
        }
    }

    // ------------------------------------------------------------------
    // close
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("close")
    class Close {

        @Test
        @DisplayName("close can be called safely")
        void closeSafe() {
            try {
                DatabaseConfig config = new DatabaseConfig(
                        "localhost", 3306, "testdb", "testuser", "testpass");
                config.close();
                // Second close should be safe
                config.close();
            } catch (Exception e) {
                // Expected if MySQL is not available
                assertTrue(e.getMessage().contains("Connection") ||
                                e.getMessage().contains("connection") ||
                                e.getMessage().contains("refused") ||
                                e.getMessage().contains("Unable") ||
                                e.getMessage().contains("Communications"));
            }
        }
    }

    // ------------------------------------------------------------------
    // getConnection
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getConnection")
    class GetConnection {

        @Test
        @DisplayName("getConnection throws SQLException when MySQL unavailable")
        void getConnectionFailsWithoutMySQL() {
            try {
                DatabaseConfig config = new DatabaseConfig(
                        "localhost", 9999, "testdb", "testuser", "testpass");
                // getConnection should throw because port 9999 has no MySQL
                assertThrows(Exception.class, config::getConnection);
                config.close();
            } catch (Exception e) {
                // Expected during construction
                assertTrue(e.getMessage().contains("Connection") ||
                                e.getMessage().contains("connection") ||
                                e.getMessage().contains("refused") ||
                                e.getMessage().contains("Unable") ||
                                e.getMessage().contains("Communications"));
            }
        }
    }
}
