package com.gsim.app;

import com.gsim.api.handlers.ImportApiHandler;
import com.gsim.importdata.ImportManager;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that FakeChromaClient is NOT used in production code paths.
 */
class ProductionDoesNotUseFakeChromaClientTest {

    @Test
    void grepMainJavaShouldNotHitFakeChromaClient() throws Exception {
        // Scan all Java source files under src/main/java for "new FakeChromaClient"
        Path mainJava = Path.of("src/main/java");
        if (!Files.isDirectory(mainJava)) {
            // Test run from different CWD — skip
            return;
        }
        var hits = new java.util.ArrayList<String>();
        try (var stream = Files.walk(mainJava)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f);
                            if (content.contains("new FakeChromaClient")) {
                                hits.add(f.toString());
                            }
                        } catch (IOException ignored) {}
                    });
        }
        assertTrue(hits.isEmpty(),
                "src/main/java must not contain 'new FakeChromaClient'. Found in: " + hits);
    }

    @Test
    void importManagerWithNullChromaReturnsNotImplemented() {
        // Create with minimal config via forTesting
        var config = com.gsim.app.AppConfig.forTesting();
        ImportManager manager = new ImportManager(config, null);
        var result = manager.doImport();
        // The error message is in logPath (last constructor param)
        assertTrue(result.logPath().contains("IMPORT_PIPELINE_NOT_IMPLEMENTED"),
                "Should return IMPORT_PIPELINE_NOT_IMPLEMENTED when chromaClient is null. Got: " + result.logPath());
    }
}
