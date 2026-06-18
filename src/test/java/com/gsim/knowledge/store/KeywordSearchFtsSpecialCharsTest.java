package com.gsim.knowledge.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that FTS5 special characters don't break keyword search.
 */
class KeywordSearchFtsSpecialCharsTest {

    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) throws Exception {
        Path dbPath = tmpDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // Insert a document with special characters in text
        var input = new com.gsim.knowledge.KnowledgeDocumentInput(
                "Test Doc",
                "This document contains AND OR NEAR operators. Also (parentheses) and colons: test.",
                "default", "agent_note", "", null);
        store.upsert(input);
    }

    @Test
    void queryWithAndShouldNotError() {
        var results = store.searchKeyword("AND OR NEAR", "default", 5);
        assertNotNull(results, "Should not throw, even with FTS operators in query");
    }

    @Test
    void queryWithParenthesesShouldNotError() {
        var results = store.searchKeyword("(test)", "default", 5);
        assertNotNull(results, "Should not throw with parentheses");
    }

    @Test
    void queryWithAsteriskShouldNotError() {
        var results = store.searchKeyword("doc*", "default", 5);
        assertNotNull(results, "Should not throw with asterisk");
    }

    @Test
    void queryWithColonShouldNotError() {
        var results = store.searchKeyword("test: value", "default", 5);
        assertNotNull(results, "Should not throw with colon");
    }

    @Test
    void queryWithCaretAndTildeShouldNotError() {
        var results = store.searchKeyword("^test~", "default", 5);
        assertNotNull(results, "Should not throw with caret or tilde");
    }

    @Test
    void queryWithPlusAndMinusShouldNotError() {
        var results = store.searchKeyword("+required -excluded", "default", 5);
        assertNotNull(results, "Should not throw with +/-");
    }

    @Test
    void normalQueryStillWorks() {
        var results = store.searchKeyword("document", "default", 5);
        assertNotNull(results);
        assertTrue(results.size() >= 1, "Should find at least one result for normal query");
    }
}
