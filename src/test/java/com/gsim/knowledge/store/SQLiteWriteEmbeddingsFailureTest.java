package com.gsim.knowledge.store;

import com.gsim.knowledge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that writeEmbeddings throws KnowledgeStoreException on failure,
 * not silently returning 0.
 */
class SQLiteWriteEmbeddingsFailureTest {

    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // Create a document and chunks first
        var input = new KnowledgeDocumentInput("Test Doc", "content for embedding test",
                "default", "agent_note", "", null);
        store.upsert(input);
    }

    @Test
    void writeEmbeddingsEmptyRowsReturnsZero() {
        // Empty rows should still return 0 (normal case)
        int result = store.writeEmbeddings(List.of());
        assertEquals(0, result, "Empty rows should return 0");
    }

    @Test
    void writeEmbeddingsToClosedStoreThrows() throws Exception {
        store.close();
        var row = new KnowledgeStore.ChunkEmbeddingRow(
                "chunk-nonexistent", "profile-1", 384, new byte[]{1, 2, 3},
                "hash123", "2026-01-01T00:00:00Z");
        assertThrows(KnowledgeStoreException.class,
                () -> store.writeEmbeddings(List.of(row)),
                "Write to closed store must throw KnowledgeStoreException");
    }

    @Test
    void writeEmbeddingsSuccessReturnsCorrectCount() {
        // Create embedding_profile first (FK constraint requires it)
        store.saveProfile(new com.gsim.knowledge.embed.EmbeddingProfile(
                "profile-1", "external", "test", "test-model", 384,
                "cosine", 1, "fp-test", "active", "2026-01-01T00:00:00Z"));

        // Get actual chunk IDs
        var results = store.searchKeyword("embedding", "default", 1);
        assertFalse(results.isEmpty(), "Should have at least one chunk");

        var row = new KnowledgeStore.ChunkEmbeddingRow(
                results.get(0).chunkId(), "profile-1", 384,
                new byte[384 * 4], "hash123", "2026-01-01T00:00:00Z");
        int count = store.writeEmbeddings(List.of(row));
        assertEquals(1, count, "Should return 1 for successful write");
    }
}
