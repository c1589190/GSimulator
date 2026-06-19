package com.gsim.knowledge.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 知识库 Schema 迁移 — 首次启动时创建所有表。
 */
public class KnowledgeSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSchemaMigrator.class);

    private static final String CREATE_DOCUMENTS = """
            CREATE TABLE IF NOT EXISTS documents (
              doc_id TEXT PRIMARY KEY,
              source_type TEXT NOT NULL,
              source_uri TEXT,
              title TEXT,
              collection TEXT NOT NULL,
              content TEXT NOT NULL,
              metadata_json TEXT,
              content_hash TEXT NOT NULL,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """;

    private static final String CREATE_CHUNKS = """
            CREATE TABLE IF NOT EXISTS chunks (
              chunk_id TEXT PRIMARY KEY,
              doc_id TEXT NOT NULL,
              collection TEXT NOT NULL,
              title TEXT,
              text TEXT NOT NULL,
              chunk_index INTEGER NOT NULL,
              start_char INTEGER,
              end_char INTEGER,
              content_hash TEXT NOT NULL,
              metadata_json TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              FOREIGN KEY(doc_id) REFERENCES documents(doc_id)
            )
            """;

    private static final String CREATE_CHUNK_FTS = """
            CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(
              chunk_id UNINDEXED,
              title,
              text,
              collection UNINDEXED
            )
            """;

    private static final String CREATE_EMBEDDING_PROFILES = """
            CREATE TABLE IF NOT EXISTS embedding_profiles (
              profile_id TEXT PRIMARY KEY,
              provider_type TEXT NOT NULL,
              provider_name TEXT,
              model_name TEXT NOT NULL,
              dimensions INTEGER NOT NULL,
              distance_metric TEXT NOT NULL,
              normalize INTEGER NOT NULL,
              config_fingerprint TEXT NOT NULL,
              status TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """;

    private static final String CREATE_CHUNK_EMBEDDINGS = """
            CREATE TABLE IF NOT EXISTS chunk_embeddings (
              chunk_id TEXT NOT NULL,
              profile_id TEXT NOT NULL,
              dimensions INTEGER NOT NULL,
              vector_blob BLOB NOT NULL,
              chunk_content_hash TEXT NOT NULL,
              embedded_at TEXT NOT NULL,
              PRIMARY KEY(chunk_id, profile_id),
              FOREIGN KEY(chunk_id) REFERENCES chunks(chunk_id),
              FOREIGN KEY(profile_id) REFERENCES embedding_profiles(profile_id)
            )
            """;

    private static final String CREATE_KNOWLEDGE_SETTINGS = """
            CREATE TABLE IF NOT EXISTS knowledge_settings (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """;

    // v2 新增列 — branch 元数据和修改链
    private static final String[][] V2_COLUMNS = {
            {"root_id", "TEXT"},
            {"branch_id", "TEXT"},
            {"revision_of", "TEXT"},
            {"target_key", "TEXT"},
            {"change_type", "TEXT DEFAULT 'created'"},
    };

    /**
     * 执行所有建表语句。幂等：使用 IF NOT EXISTS。
     * V2: 为 documents 表新增 branch metadata 列（root_id, branch_id, revision_of, target_key, change_type）。
     */
    public void migrate(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_DOCUMENTS);
            stmt.execute(CREATE_CHUNKS);
            stmt.execute(CREATE_CHUNK_FTS);
            stmt.execute(CREATE_EMBEDDING_PROFILES);
            stmt.execute(CREATE_CHUNK_EMBEDDINGS);
            stmt.execute(CREATE_KNOWLEDGE_SETTINGS);
        }

        // V2 迁移：为 documents 表添加 branch metadata 列
        for (String[] col : V2_COLUMNS) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE documents ADD COLUMN " + col[0] + " " + col[1]);
            } catch (SQLException e) {
                // 列已存在 — 忽略
                log.debug("Column {} may already exist: {}", col[0], e.getMessage());
            }
        }

        log.info("Knowledge schema migration complete");
    }
}
