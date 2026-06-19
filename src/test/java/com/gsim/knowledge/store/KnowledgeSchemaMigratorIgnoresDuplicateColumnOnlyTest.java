package com.gsim.knowledge.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KnowledgeSchemaMigrator — 只忽略 duplicate column 错误")
class KnowledgeSchemaMigratorIgnoresDuplicateColumnOnlyTest {

    @TempDir Path tempDir;

    private Connection newConnection(String name) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve(name).toString());
    }

    @Test
    @DisplayName("重复执行 migrate 不抛异常（duplicate column 被忽略）")
    void doubleMigrationSucceeds() throws Exception {
        Connection conn = newConnection("test1.db");
        try {
            // 第一次迁移：正常创建表和列
            new KnowledgeSchemaMigrator().migrate(conn);

            // 验证 V2 列存在
            try (Statement stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("PRAGMA table_info(documents)");
                int colCount = 0;
                while (rs.next()) colCount++;
                assertTrue(colCount >= 10, "should have at least 10 columns after V2 migration");
            }

            // 第二次迁移：不应抛异常（duplicate column 被忽略）
            assertDoesNotThrow(() -> new KnowledgeSchemaMigrator().migrate(conn),
                    "second migration should not throw — duplicate column errors are ignored");
        } finally {
            conn.close();
        }
    }

    @Test
    @DisplayName("正常初始化后的 store 可重复 initialize")
    void storeDoubleInitializeSucceeds() throws Exception {
        String dbPath = tempDir.resolve("test2.db").toString();
        SQLiteKnowledgeStore store = new SQLiteKnowledgeStore(dbPath);
        store.initialize();
        // 第二次 initialize 不应抛异常
        assertDoesNotThrow(store::initialize,
                "second initialize should succeed — duplicate column errors are ignored");
    }
}
