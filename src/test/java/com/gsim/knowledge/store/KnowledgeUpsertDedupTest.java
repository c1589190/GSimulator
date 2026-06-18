package com.gsim.knowledge.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that upsert deduplicates documents with same content_hash.
 */
class KnowledgeUpsertDedupTest {

    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    void sameContentSameUriReturnsDeduplicated() {
        var input = new com.gsim.knowledge.KnowledgeDocumentInput(
                "Test Title", "Some content for dedup test",
                "default", "agent_note", "http://example.com/ref", null);

        var r1 = store.upsert(input);
        assertTrue(r1.success());
        assertEquals("KEYWORD_ONLY", r1.status());

        // Same input again — should get deduplicated
        var r2 = store.upsert(input);
        assertTrue(r2.success());
        assertEquals("DEDUPLICATED_EXISTING", r2.status());
        assertEquals(r1.docId(), r2.docId(), "Dedup should return same docId");
    }

    @Test
    void sameContentNoUriSameTitleReturnsDeduplicated() {
        var input = new com.gsim.knowledge.KnowledgeDocumentInput(
                "My Note", "Note content here", "notes", "agent_note", "", null);

        var r1 = store.upsert(input);
        assertTrue(r1.success());
        assertEquals("KEYWORD_ONLY", r1.status());

        var r2 = store.upsert(input);
        assertTrue(r2.success());
        assertEquals("DEDUPLICATED_EXISTING", r2.status());
        assertEquals(r1.docId(), r2.docId());
    }

    @Test
    void differentContentCreatesNewDoc() {
        var input1 = new com.gsim.knowledge.KnowledgeDocumentInput(
                "T", "Content A", "default", "agent_note", "", null);
        var input2 = new com.gsim.knowledge.KnowledgeDocumentInput(
                "T", "Content B — different", "default", "agent_note", "", null);

        var r1 = store.upsert(input1);
        var r2 = store.upsert(input2);
        assertTrue(r1.success());
        assertTrue(r2.success());
        assertNotEquals(r1.docId(), r2.docId(), "Different content should get different docId");
    }
}
