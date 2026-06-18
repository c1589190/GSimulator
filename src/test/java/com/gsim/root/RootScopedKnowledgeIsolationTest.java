package com.gsim.root;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.scope.KnowledgeScope;
import com.gsim.knowledge.scope.ScopedKnowledgeStoreFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that different roots have physically isolated knowledge stores.
 */
class RootScopedKnowledgeIsolationTest {

    private Path dataRoot;
    private ScopedKnowledgeStoreFactory factory;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) {
        dataRoot = tmpDir.resolve("data");
        factory = new ScopedKnowledgeStoreFactory(null);
        // Create root directories
        try {
            Files.createDirectories(dataRoot.resolve("worlds").resolve("root-a").resolve("knowledge"));
            Files.createDirectories(dataRoot.resolve("worlds").resolve("root-b").resolve("knowledge"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void rootAAndRootBHaveDifferentDbFiles() {
        KnowledgeScope scopeA = KnowledgeScope.of(dataRoot, "root-a");
        KnowledgeScope scopeB = KnowledgeScope.of(dataRoot, "root-b");

        var storeA = factory.getOrCreateStore(scopeA);
        var storeB = factory.getOrCreateStore(scopeB);

        assertNotSame(storeA, storeB, "Different roots must have different store instances");

        // Upsert into root A
        KnowledgeDocumentInput inputA = new KnowledgeDocumentInput(
                "Doc in A", "Content for root A only", "default", "agent_note", "", null);
        var resultA = storeA.upsert(inputA);
        assertTrue(resultA.success());

        // Keyword search in root B should NOT find it
        var resultsB = storeB.searchKeyword("Content for root A only", "default", 5);
        assertTrue(resultsB.isEmpty(),
                "Root B must not see root A's documents. Got: " + resultsB.size() + " results");

        // Keyword search in root A SHOULD find it
        var resultsA = storeA.searchKeyword("Content for root A only", "default", 5);
        assertFalse(resultsA.isEmpty(), "Root A should find its own documents");

        factory.closeAll();
    }

    @Test
    void rootAAndRootBHaveDifferentDbFilesOnDisk() {
        KnowledgeScope scopeA = KnowledgeScope.of(dataRoot, "root-a");
        KnowledgeScope scopeB = KnowledgeScope.of(dataRoot, "root-b");

        var storeA = factory.getOrCreateStore(scopeA);
        var storeB = factory.getOrCreateStore(scopeB);

        assertNotEquals(scopeA.knowledgeDbPath(), scopeB.knowledgeDbPath(),
                "DB files should be at different paths");
        assertTrue(scopeA.knowledgeDbPath().toString().contains("root-a"));
        assertTrue(scopeB.knowledgeDbPath().toString().contains("root-b"));

        factory.closeAll();
    }
}
