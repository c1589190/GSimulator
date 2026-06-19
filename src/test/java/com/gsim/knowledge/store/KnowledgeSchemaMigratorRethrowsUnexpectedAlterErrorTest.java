package com.gsim.knowledge.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KnowledgeSchemaMigrator — 非 duplicate column 的 SQL 异常必须重新抛出")
class KnowledgeSchemaMigratorRethrowsUnexpectedAlterErrorTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("已关闭的连接上调用 migrate 抛 SQLException（非 duplicate column）")
    void closedConnectionThrowsSQLException() throws Exception {
        String dbPath = tempDir.resolve("test.db").toString();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        // 先正常迁移一次
        new KnowledgeSchemaMigrator().migrate(conn);

        // 关闭连接
        conn.close();

        // 在已关闭的连接上调用 migrate → 应抛出 SQLException
        assertThrows(SQLException.class, () -> {
            new KnowledgeSchemaMigrator().migrate(conn);
        }, "closed connection should cause SQLException that is NOT swallowed");
    }

    @Test
    @DisplayName("正常重复迁移不抛异常（证明 duplicate column 被正确忽略）")
    void normalDoubleMigrationDoesNotThrow() throws Exception {
        String dbPath = tempDir.resolve("test2.db").toString();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try {
            new KnowledgeSchemaMigrator().migrate(conn);
            // 第二次迁移应成功 — duplicate column 被忽略
            assertDoesNotThrow(() -> new KnowledgeSchemaMigrator().migrate(conn));
        } finally {
            conn.close();
        }
    }
}
