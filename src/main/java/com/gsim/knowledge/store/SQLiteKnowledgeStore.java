package com.gsim.knowledge.store;

import com.gsim.knowledge.*;
import com.gsim.knowledge.chunk.Chunker;
import com.gsim.knowledge.embed.EmbeddingProfile;
import com.gsim.knowledge.embed.EmbeddingVector;
import com.gsim.knowledge.embed.VectorCodec;
import com.gsim.util.IdGenerator;
import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 知识库存储实现 — 使用 SQLite + FTS5 管理文档/块/embeddings。
 */
public class SQLiteKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(SQLiteKnowledgeStore.class);

    private final String dbPath;
    private final Chunker chunker;
    private Connection connection;

    public SQLiteKnowledgeStore(String dbPath) {
        this.dbPath = dbPath;
        this.chunker = new Chunker();
    }

    /** 初始化：打开连接并执行 schema 迁移。 */
    public void initialize() throws SQLException {
        Connection conn = getConnection();
        new KnowledgeSchemaMigrator().migrate(conn);
        // 确保默认设置存在
        if (getSetting(KnowledgeSettings.KEY_KNOWLEDGE_STORE_VERSION).isEmpty()) {
            setSetting(KnowledgeSettings.KEY_KNOWLEDGE_STORE_VERSION, KnowledgeSettings.CURRENT_VERSION);
        }
        if (getSetting(KnowledgeSettings.KEY_DEFAULT_COLLECTION).isEmpty()) {
            setSetting(KnowledgeSettings.KEY_DEFAULT_COLLECTION, "default");
        }
        log.info("SQLiteKnowledgeStore initialized: {}", dbPath);
    }

    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            // 启用外键约束
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        }
        return connection;
    }

    // ---- CRUD ----

    @Override
    public KnowledgeUpsertResult upsert(KnowledgeDocumentInput input) {
        String docId = IdGenerator.knowledgeDocId();
        String now = Instant.now().toString();
        String contentHash = Chunker.sha256(input.content());
        String metadataJson = input.metadata() != null && !input.metadata().isEmpty()
                ? JsonUtils.toJson(input.metadata()) : "";

        try {
            Connection conn = getConnection();
            conn.setAutoCommit(false);
            try {
                // 1. 写 documents
                String docSql = """
                        INSERT INTO documents (doc_id, source_type, source_uri, title, collection,
                          content, metadata_json, content_hash, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(docSql)) {
                    ps.setString(1, docId);
                    ps.setString(2, input.sourceType());
                    ps.setString(3, input.sourceUri());
                    ps.setString(4, input.title());
                    ps.setString(5, input.collection());
                    ps.setString(6, input.content());
                    ps.setString(7, metadataJson);
                    ps.setString(8, contentHash);
                    ps.setString(9, now);
                    ps.setString(10, now);
                    ps.executeUpdate();
                }

                // 2. 切 chunks
                List<KnowledgeChunk> chunks = chunker.chunk(docId, input.title(),
                        input.content(), input.collection(), metadataJson);

                // 3. 写 chunks + fts
                String chunkSql = """
                        INSERT INTO chunks (chunk_id, doc_id, collection, title, text,
                          chunk_index, start_char, end_char, content_hash, metadata_json,
                          created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                String ftsSql = "INSERT INTO chunk_fts (chunk_id, title, text, collection) VALUES (?, ?, ?, ?)";

                try (PreparedStatement psChunk = conn.prepareStatement(chunkSql);
                     PreparedStatement psFts = conn.prepareStatement(ftsSql)) {
                    for (KnowledgeChunk c : chunks) {
                        psChunk.setString(1, c.chunkId());
                        psChunk.setString(2, c.docId());
                        psChunk.setString(3, c.collection());
                        psChunk.setString(4, c.title());
                        psChunk.setString(5, c.text());
                        psChunk.setInt(6, c.chunkIndex());
                        psChunk.setInt(7, c.startChar());
                        psChunk.setInt(8, c.endChar());
                        psChunk.setString(9, c.contentHash());
                        psChunk.setString(10, c.metadataJson());
                        psChunk.setString(11, c.createdAt());
                        psChunk.setString(12, c.updatedAt());
                        psChunk.executeUpdate();

                        psFts.setString(1, c.chunkId());
                        psFts.setString(2, c.title());
                        psFts.setString(3, c.text());
                        psFts.setString(4, c.collection());
                        psFts.executeUpdate();
                    }
                }

                conn.commit();

                // 返回 KEYWORD_ONLY（embeddings 由调用者通过 embedMissing 补充）
                return KnowledgeUpsertResult.keywordOnly(docId, chunks.size());

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("upsert failed: {}", e.getMessage());
            return KnowledgeUpsertResult.fail(e.getMessage());
        }
    }

    @Override
    public KnowledgeUpdateResult update(String docId, KnowledgeDocumentInput input) {
        String now = Instant.now().toString();
        String contentHash = Chunker.sha256(input.content());
        String metadataJson = input.metadata() != null && !input.metadata().isEmpty()
                ? JsonUtils.toJson(input.metadata()) : "";

        try {
            Connection conn = getConnection();

            // 检查文档是否存在
            Optional<KnowledgeDocument> existing = getDocument(docId);
            if (existing.isEmpty()) {
                return KnowledgeUpdateResult.notFound(docId);
            }

            conn.setAutoCommit(false);
            try {
                // 1. 删除旧 chunks / fts / embeddings
                int oldChunks = deleteChunksForDoc(conn, docId);
                int oldEmbeddings = deleteEmbeddingsForDoc(conn, docId);

                // 2. 更新 document
                String docSql = """
                        UPDATE documents SET title=?, content=?, source_type=?, source_uri=?,
                          collection=?, metadata_json=?, content_hash=?, updated_at=?
                        WHERE doc_id=?
                        """;
                try (PreparedStatement ps = conn.prepareStatement(docSql)) {
                    ps.setString(1, input.title());
                    ps.setString(2, input.content());
                    ps.setString(3, input.sourceType());
                    ps.setString(4, input.sourceUri());
                    ps.setString(5, input.collection());
                    ps.setString(6, metadataJson);
                    ps.setString(7, contentHash);
                    ps.setString(8, now);
                    ps.setString(9, docId);
                    ps.executeUpdate();
                }

                // 3. 重建 chunks + fts
                List<KnowledgeChunk> chunks = chunker.chunk(docId, input.title(),
                        input.content(), input.collection(), metadataJson);

                String chunkSql = """
                        INSERT INTO chunks (chunk_id, doc_id, collection, title, text,
                          chunk_index, start_char, end_char, content_hash, metadata_json,
                          created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                String ftsSql = "INSERT INTO chunk_fts (chunk_id, title, text, collection) VALUES (?, ?, ?, ?)";

                try (PreparedStatement psChunk = conn.prepareStatement(chunkSql);
                     PreparedStatement psFts = conn.prepareStatement(ftsSql)) {
                    for (KnowledgeChunk c : chunks) {
                        psChunk.setString(1, c.chunkId());
                        psChunk.setString(2, c.docId());
                        psChunk.setString(3, c.collection());
                        psChunk.setString(4, c.title());
                        psChunk.setString(5, c.text());
                        psChunk.setInt(6, c.chunkIndex());
                        psChunk.setInt(7, c.startChar());
                        psChunk.setInt(8, c.endChar());
                        psChunk.setString(9, c.contentHash());
                        psChunk.setString(10, c.metadataJson());
                        psChunk.setString(11, c.createdAt());
                        psChunk.setString(12, c.updatedAt());
                        psChunk.executeUpdate();

                        psFts.setString(1, c.chunkId());
                        psFts.setString(2, c.title());
                        psFts.setString(3, c.text());
                        psFts.setString(4, c.collection());
                        psFts.executeUpdate();
                    }
                }

                conn.commit();
                return KnowledgeUpdateResult.keywordOnly(docId, oldChunks, chunks.size());

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("update failed: {}", e.getMessage());
            return KnowledgeUpdateResult.fail(docId, e.getMessage());
        }
    }

    @Override
    public KnowledgeDeleteResult delete(String docId) {
        try {
            Connection conn = getConnection();

            // 检查是否存在
            Optional<KnowledgeDocument> existing = getDocument(docId);
            if (existing.isEmpty()) {
                return KnowledgeDeleteResult.notFound(docId);
            }

            conn.setAutoCommit(false);
            try {
                int embeddingsDeleted = deleteEmbeddingsForDoc(conn, docId);
                int chunksDeleted = deleteChunksForDoc(conn, docId);

                // 删除 document
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM documents WHERE doc_id = ?")) {
                    ps.setString(1, docId);
                    ps.executeUpdate();
                }

                conn.commit();
                return KnowledgeDeleteResult.ok(docId, chunksDeleted, embeddingsDeleted);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("delete failed: {}", e.getMessage());
            return KnowledgeDeleteResult.fail(docId, e.getMessage());
        }
    }

    private int deleteChunksForDoc(Connection conn, String docId) throws SQLException {
        // 先删 fts
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chunk_fts WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE doc_id = ?)")) {
            ps.setString(1, docId);
            ps.executeUpdate();
        }
        // 再删 chunks
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chunks WHERE doc_id = ?")) {
            ps.setString(1, docId);
            return ps.executeUpdate();
        }
    }

    private int deleteEmbeddingsForDoc(Connection conn, String docId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chunk_embeddings WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE doc_id = ?)")) {
            ps.setString(1, docId);
            return ps.executeUpdate();
        }
    }

    @Override
    public Optional<KnowledgeDocument> getDocument(String docId) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT * FROM documents WHERE doc_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapDocument(rs));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("getDocument failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Optional<KnowledgeChunk> getChunk(String chunkId) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT * FROM chunks WHERE chunk_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, chunkId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapChunk(rs));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("getChunk failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public List<KnowledgeSearchResult> searchKeyword(String query, String collection, int topK) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        try {
            Connection conn = getConnection();

            // 1. FTS5 MATCH
            String ftsSql = """
                    SELECT c.chunk_id, c.doc_id, c.title, c.text, c.collection,
                           d.source_uri, fts.rank
                    FROM chunk_fts fts
                    JOIN chunks c ON fts.chunk_id = c.chunk_id
                    JOIN documents d ON c.doc_id = d.doc_id
                    WHERE chunk_fts MATCH ? AND c.collection = ?
                    ORDER BY rank
                    LIMIT ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(ftsSql)) {
                ps.setString(1, escapeFts5(query));
                ps.setString(2, collection);
                ps.setInt(3, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String chunkId = rs.getString("chunk_id");
                        String docId = rs.getString("doc_id");
                        String title = rs.getString("title");
                        String text = rs.getString("text");
                        String col = rs.getString("collection");
                        String sourceUri = rs.getString("source_uri");
                        double rank = rs.getDouble("rank");
                        String snippet = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                        results.add(new KnowledgeSearchResult(chunkId, docId, title, sourceUri,
                                col, snippet, 0.0, -rank, -rank, null, "keyword"));
                    }
                }
            }

            // 2. LIKE fallback — 如果 FTS 结果不足，用 LIKE 补充
            if (results.size() < topK) {
                int remaining = topK - results.size();
                String likeSql = """
                        SELECT c.chunk_id, c.doc_id, c.title, c.text, c.collection,
                               d.source_uri
                        FROM chunks c
                        JOIN documents d ON c.doc_id = d.doc_id
                        WHERE c.collection = ?
                          AND (c.title LIKE ? OR c.text LIKE ? OR d.source_uri LIKE ?)
                        ORDER BY c.chunk_index
                        LIMIT ?
                        """;
                String likePattern = "%" + query + "%";
                try (PreparedStatement ps = conn.prepareStatement(likeSql)) {
                    ps.setString(1, collection);
                    ps.setString(2, likePattern);
                    ps.setString(3, likePattern);
                    ps.setString(4, likePattern);
                    ps.setInt(5, remaining);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String chunkId = rs.getString("chunk_id");
                            String docId = rs.getString("doc_id");
                            String title = rs.getString("title");
                            String text = rs.getString("text");
                            String col = rs.getString("collection");
                            String sourceUri = rs.getString("source_uri");
                            String snippet = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                            // 避免重复
                            boolean dup = results.stream().anyMatch(r -> r.chunkId().equals(chunkId));
                            if (!dup) {
                                results.add(new KnowledgeSearchResult(chunkId, docId, title, sourceUri,
                                        col, snippet, 0.0, 0.1, 0.1, null, "keyword"));
                            }
                        }
                    }
                }
            }

        } catch (SQLException e) {
            log.error("searchKeyword failed: {}", e.getMessage());
        }
        return results;
    }

    /** 对 FTS5 查询字符串做基本转义 */
    private String escapeFts5(String query) {
        // FTS5 用双引号包裹精确短语，否则作为独立 token
        // 简单处理：去掉 FTS5 特殊字符
        return query.replaceAll("[\"*]", " ").trim();
    }

    @Override
    public List<ChunkEmbeddingRow> getChunkEmbeddings(String collection, String profileId) {
        List<ChunkEmbeddingRow> rows = new ArrayList<>();
        try {
            Connection conn = getConnection();
            String sql = """
                    SELECT ce.chunk_id, ce.profile_id, ce.dimensions, ce.vector_blob,
                           ce.chunk_content_hash, ce.embedded_at
                    FROM chunk_embeddings ce
                    JOIN chunks c ON ce.chunk_id = c.chunk_id
                    WHERE c.collection = ? AND ce.profile_id = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, collection);
                ps.setString(2, profileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ChunkEmbeddingRow(
                                rs.getString("chunk_id"),
                                rs.getString("profile_id"),
                                rs.getInt("dimensions"),
                                rs.getBytes("vector_blob"),
                                rs.getString("chunk_content_hash"),
                                rs.getString("embedded_at")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("getChunkEmbeddings failed: {}", e.getMessage());
        }
        return rows;
    }

    @Override
    public int writeEmbeddings(List<ChunkEmbeddingRow> rows) {
        if (rows.isEmpty()) return 0;
        try {
            Connection conn = getConnection();
            String sql = """
                    INSERT OR REPLACE INTO chunk_embeddings
                      (chunk_id, profile_id, dimensions, vector_blob, chunk_content_hash, embedded_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            int count = 0;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ChunkEmbeddingRow row : rows) {
                    ps.setString(1, row.chunkId());
                    ps.setString(2, row.profileId());
                    ps.setInt(3, row.dimensions());
                    ps.setBytes(4, row.vectorBlob());
                    ps.setString(5, row.chunkContentHash());
                    ps.setString(6, row.embeddedAt());
                    ps.addBatch();
                    count++;
                }
                ps.executeBatch();
            }
            return count;
        } catch (SQLException e) {
            log.error("writeEmbeddings failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<String> findChunksMissingEmbedding(String collection, String profileId) {
        List<String> chunkIds = new ArrayList<>();
        try {
            Connection conn = getConnection();
            String sql = """
                    SELECT c.chunk_id FROM chunks c
                    WHERE c.collection = ?
                      AND c.chunk_id NOT IN (
                        SELECT ce.chunk_id FROM chunk_embeddings ce WHERE ce.profile_id = ?
                      )
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, collection);
                ps.setString(2, profileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        chunkIds.add(rs.getString("chunk_id"));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("findChunksMissingEmbedding failed: {}", e.getMessage());
        }
        return chunkIds;
    }

    @Override
    public KnowledgeStoreStatus status() {
        try {
            Connection conn = getConnection();
            int docCount = count(conn, "documents");
            int chunkCount = count(conn, "chunks");
            int profileCount = count(conn, "embedding_profiles");
            int embeddingCount = count(conn, "chunk_embeddings");
            String activeProfileId = getSetting(KnowledgeSettings.KEY_ACTIVE_EMBEDDING_PROFILE_ID).orElse(null);
            String version = getSetting(KnowledgeSettings.KEY_KNOWLEDGE_STORE_VERSION).orElse("0");
            String defaultCol = getSetting(KnowledgeSettings.KEY_DEFAULT_COLLECTION).orElse("default");

            return new KnowledgeStoreStatus(dbPath, docCount, chunkCount, activeProfileId,
                    profileCount, embeddingCount, true, version, defaultCol);
        } catch (SQLException e) {
            log.error("status failed: {}", e.getMessage());
            return new KnowledgeStoreStatus(dbPath, 0, 0, null, 0, 0,
                    false, "0", "default");
        }
    }

    @Override
    public Optional<String> getSetting(String key) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT value FROM knowledge_settings WHERE key = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("value"));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("getSetting failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void setSetting(String key, String value) {
        try {
            Connection conn = getConnection();
            String sql = "INSERT OR REPLACE INTO knowledge_settings (key, value) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("setSetting failed: {}", e.getMessage());
        }
    }

    @Override
    public List<EmbeddingProfile> listProfiles() {
        List<EmbeddingProfile> profiles = new ArrayList<>();
        try {
            Connection conn = getConnection();
            String sql = "SELECT * FROM embedding_profiles ORDER BY created_at DESC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    profiles.add(mapProfile(rs));
                }
            }
        } catch (SQLException e) {
            log.error("listProfiles failed: {}", e.getMessage());
        }
        return profiles;
    }

    @Override
    public Optional<EmbeddingProfile> getProfile(String profileId) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT * FROM embedding_profiles WHERE profile_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, profileId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapProfile(rs));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("getProfile failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void saveProfile(EmbeddingProfile profile) {
        try {
            Connection conn = getConnection();
            String sql = """
                    INSERT OR REPLACE INTO embedding_profiles
                      (profile_id, provider_type, provider_name, model_name, dimensions,
                       distance_metric, normalize, config_fingerprint, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, profile.profileId());
                ps.setString(2, profile.providerType());
                ps.setString(3, profile.providerName());
                ps.setString(4, profile.modelName());
                ps.setInt(5, profile.dimensions());
                ps.setString(6, profile.distanceMetric());
                ps.setInt(7, profile.normalize());
                ps.setString(8, profile.configFingerprint());
                ps.setString(9, profile.status());
                ps.setString(10, profile.createdAt());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("saveProfile failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Failed to close SQLite connection: {}", e.getMessage());
            }
        }
    }

    // ---- Row mapping helpers ----

    private KnowledgeDocument mapDocument(ResultSet rs) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("doc_id"),
                rs.getString("source_type"),
                rs.getString("source_uri"),
                rs.getString("title"),
                rs.getString("collection"),
                rs.getString("content"),
                rs.getString("metadata_json"),
                rs.getString("content_hash"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    private KnowledgeChunk mapChunk(ResultSet rs) throws SQLException {
        return new KnowledgeChunk(
                rs.getString("chunk_id"),
                rs.getString("doc_id"),
                rs.getString("collection"),
                rs.getString("title"),
                rs.getString("text"),
                rs.getInt("chunk_index"),
                rs.getInt("start_char"),
                rs.getInt("end_char"),
                rs.getString("content_hash"),
                rs.getString("metadata_json"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    private EmbeddingProfile mapProfile(ResultSet rs) throws SQLException {
        return new EmbeddingProfile(
                rs.getString("profile_id"),
                rs.getString("provider_type"),
                rs.getString("provider_name"),
                rs.getString("model_name"),
                rs.getInt("dimensions"),
                rs.getString("distance_metric"),
                rs.getInt("normalize"),
                rs.getString("config_fingerprint"),
                rs.getString("status"),
                rs.getString("created_at")
        );
    }

    private int count(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.getInt(1);
        }
    }
}
