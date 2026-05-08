package com.diffguard.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * MySQL 数据源配置（HikariCP 连接池）。
 */
public class DatabaseConfig implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private final HikariDataSource dataSource;

    public DatabaseConfig(String host, int port, String dbName, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName +
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&charset=utf8mb4");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        config.setPoolName("diffguard-hikari");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "50");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(config);
        log.info("MySQL connection pool initialized: {}:{}/{}/{}", host, port, dbName, user);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 从环境变量构建。
     */
    public static DatabaseConfig fromEnv() {
        String host = env("MYSQL_HOST", "localhost");
        int port = Integer.parseInt(env("MYSQL_PORT", "3306"));
        String db = env("MYSQL_DB", "diffguard");
        String user = env("MYSQL_USER", "diffguard");
        String password = env("MYSQL_PASSWORD", null);
        if (password == null) {
            throw new IllegalStateException("MYSQL_PASSWORD environment variable is required");
        }
        return new DatabaseConfig(host, port, db, user, password);
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val.trim() : defaultVal;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("MySQL connection pool closed");
        }
    }
}
