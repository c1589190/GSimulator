package com.gsim.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * SQLite 存储 — 关系型存储（未来扩展用）。
 * Phase 2 只提供基本连接，不做表初始化。
 */
public class SqliteStorage {

    private static final Logger log = LoggerFactory.getLogger(SqliteStorage.class);

    private final String dbPath;
    private Connection connection;

    public SqliteStorage(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * 获取数据库连接。
     */
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            log.info("SQLite connection opened: {}", dbPath);
        }
        return connection;
    }

    /**
     * 初始化表结构（Phase 3+）。
     */
    public void initialize() throws SQLException {
        // TODO Phase 3+: 创建 campaigns, turns, player_actions 等表
        log.info("SQLite storage initialized (no tables yet)");
    }

    /**
     * 关闭连接。
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Failed to close SQLite connection: {}", e.getMessage());
            }
        }
    }
}
